package com.thatgfsj.qaa.core.inference

import java.io.ByteArrayOutputStream

/** 构造最小 GGUF 头夹具（v2 / v3，可注水词表与尾字节），供头解析与门禁单测共用。 */
internal object GgufFixture {

    fun build(
        version: Int = 3,
        architecture: String? = "qwen2",
        fileType: Long? = 2L,
        blockCount: Int? = 32,
        headCount: Int? = 32,
        headCountKv: List<Int>? = listOf(8),
        embeddingLength: Int? = 2048,
        keyLength: Int? = 128,
        valueLength: Int? = 128,
        tokenCount: Int = 0,
        tailBytes: Int = 0,
        tensors: List<FixtureTensor> = emptyList(),
        tensorCount: Int? = null,
    ): ByteArray {
        val arch = architecture ?: "qwen2"
        val kvs = mutableListOf<ByteArray>()
        architecture?.let { kvs += kvString("general.architecture", it) }
        fileType?.let { kvs += kvU32("general.file_type", it) }
        blockCount?.let { kvs += kvU32(arch + ".block_count", it.toLong()) }
        headCount?.let { kvs += kvU32(arch + ".attention.head_count", it.toLong()) }
        headCountKv?.let { heads ->
            if (heads.size == 1) {
                kvs += kvU32(arch + ".attention.head_count_kv", heads[0].toLong())
            } else {
                kvs += kvU32Array(arch + ".attention.head_count_kv", heads)
            }
        }
        embeddingLength?.let { kvs += kvU32(arch + ".embedding_length", it.toLong()) }
        keyLength?.let { kvs += kvU32(arch + ".attention.key_length", it.toLong()) }
        valueLength?.let { kvs += kvU32(arch + ".attention.value_length", it.toLong()) }
        if (tokenCount > 0) {
            kvs += kvStringArray("tokenizer.ggml.tokens", List(tokenCount) { "tok" + it })
        }
        val out = ByteArrayOutputStream()
        writeU32(out, GgufHeaderReader.GGUF_MAGIC)
        writeU32(out, version.toLong())
        writeU64(out, (tensorCount ?: tensors.size).toLong())
        writeU64(out, kvs.size.toLong())
        kvs.forEach { out.write(it) }
        tensors.forEach { writeTensorInfo(out, it) }
        if (tailBytes > 0) out.write(ByteArray(tailBytes))
        return out.toByteArray()
    }

    /** 张量目录条目（夹具用，不含张量数据）。 */
    data class FixtureTensor(
        val name: String,
        val dims: List<Long>,
        val type: GgufQuantization,
        /** 覆盖写入的 ggml 类型 id：模拟本版本未收录的新量化类型。 */
        val typeId: Long? = null,
    )

    /** 二维 matmul 权重（`blk.N.attn_*` / `blk.N.ffn_*`），量化类型按需指定。 */
    fun matmul(name: String, type: GgufQuantization, rows: Long = 128, cols: Long = 256): FixtureTensor =
        FixtureTensor(name = name, dims = listOf(rows, cols), type = type)

    private fun writeTensorInfo(out: ByteArrayOutputStream, tensor: FixtureTensor) {
        writeString(out, tensor.name)
        writeU32(out, tensor.dims.size.toLong())
        tensor.dims.forEach { writeU64(out, it) }
        writeU32(out, tensor.typeId ?: tensor.type.tensorTypeId ?: 0L)
        writeU64(out, 0L) // 数据偏移：头解析不读
    }

    /** 只写 magic 的坏头（magic 校验用）。 */
    fun wrongMagic(): ByteArray {
        val out = ByteArrayOutputStream()
        writeU32(out, 0x12345678L)
        writeU32(out, 3L)
        writeU64(out, 0L)
        writeU64(out, 0L)
        return out.toByteArray()
    }

    private fun kvString(key: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, key)
        writeU32(out, TYPE_STRING)
        writeString(out, value)
        return out.toByteArray()
    }

    private fun kvU32(key: String, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, key)
        writeU32(out, TYPE_UINT32)
        writeU32(out, value)
        return out.toByteArray()
    }

    private fun kvU32Array(key: String, values: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, key)
        writeU32(out, TYPE_ARRAY)
        writeU32(out, TYPE_UINT32)
        writeU64(out, values.size.toLong())
        values.forEach { writeU32(out, it.toLong()) }
        return out.toByteArray()
    }

    private fun kvStringArray(key: String, values: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, key)
        writeU32(out, TYPE_ARRAY)
        writeU32(out, TYPE_STRING)
        writeU64(out, values.size.toLong())
        values.forEach { writeString(out, it) }
        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU64(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        for (shift in 0 until 32 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    private fun writeU64(out: ByteArrayOutputStream, value: Long) {
        for (shift in 0 until 64 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    private const val TYPE_STRING = 8L
    private const val TYPE_ARRAY = 9L
    private const val TYPE_UINT32 = 4L
}
