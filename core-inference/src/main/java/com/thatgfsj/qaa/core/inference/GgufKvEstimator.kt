package com.thatgfsj.qaa.core.inference

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** KV cache 估算来源（#112）。 */
enum class KvEstimateSource {

    /** 依据 GGUF 头元数据（层数 / KV heads / head_dim）；f16 精度。 */
    GGUF_METADATA,

    /** 头部缺失或解析失败：回退经验值 [GgufKvEstimator.FALLBACK_BYTES_PER_TOKEN]。 */
    FALLBACK,
}

/** GGUF 头解析出的注意力结构（用于 /ctx 回执文案）。 */
data class GgufKvLayout(
    /** general.architecture（如 llama / qwen2）；未知为 null。 */
    val architecture: String?,
    /** 层数（*.block_count）。 */
    val blockCount: Int,
    /** K 头维度（key_length，缺失时 embedding_length ÷ head_count）。 */
    val headDim: Int,
    /** V 头维度（value_length，缺失时与 K 同维）。 */
    val valueDim: Int,
    /** 每层 KV heads（部分混合注意力模型逐层不同）。 */
    val headsPerLayer: List<Int>,
) {

    /** 展示用 KV heads 摘要：全层相同时为单值，否则去重后以 / 连接。 */
    val kvHeadsSummary: String
        get() = headsPerLayer.distinct().joinToString("/")
}

/** KV cache 估算结果。 */
data class KvEstimate(
    /** 每 token 的 KV cache 字节数（f16）。 */
    val bytesPerToken: Long,
    val source: KvEstimateSource,
    /** 解析成功时的结构摘要；回退时为 null。 */
    val layout: GgufKvLayout? = null,
)

/**
 * GGUF 头 KV cache 估算（#112；同时是 #102 GGUF 元数据解析底座的最小实现）。
 *
 * 只解析头部元数据（不读 tensor 数据），f16 公式：
 *
 *     bytes/token = Σ_layer kv_heads × (key_dim + value_dim) × 2 字节
 *
 * 其中 key_dim/value_dim 优先取 `*.attention.key_length` / `*.attention.value_length`；
 * 缺失时用 `embedding_length ÷ attention.head_count` 推 head_dim。层数取 `*.block_count`，
 * KV heads 取 `*.attention.head_count_kv`（标量或逐层数组，兼容混合注意力）。
 *
 * 读取策略：只保留上述少量键，其余元数据（tokenizer 词表等大数组）只做跳过，避免把
 * 上百 MB 词表读进内存；坏 magic / 版本过旧 / 字段缺失 / IO 异常一律返回 fallback，
 * 由调用方在回执中标注「估算」。
 */
object GgufKvEstimator {

    /** 回退经验值：4B 级 GQA f16 约 144 KiB/token（#106 真机取证）。 */
    const val FALLBACK_BYTES_PER_TOKEN: Long = 144L * 1024L

    /** 单次最多跳过的元数据数组元素数：防异常计数导致解析卡死。 */
    private const val MAX_ARRAY_COUNT = 20_000_000L

    /** 单个字符串（键名 / 值）长度上限。 */
    private const val MAX_STRING_BYTES = 4L * 1024L * 1024L

    /** 元数据键值对数量上限（正常模型 < 1e5）。 */
    private const val MAX_KV_COUNT = 1_000_000L

    private const val GGUF_MAGIC = 0x46554747L
    private const val VERSION_MIN = 2L
    private const val VERSION_MAX = 3L

    private const val TYPE_UINT8 = 0L
    private const val TYPE_INT8 = 1L
    private const val TYPE_UINT16 = 2L
    private const val TYPE_INT16 = 3L
    private const val TYPE_UINT32 = 4L
    private const val TYPE_INT32 = 5L
    private const val TYPE_FLOAT32 = 6L
    private const val TYPE_BOOL = 7L
    private const val TYPE_STRING = 8L
    private const val TYPE_ARRAY = 9L
    private const val TYPE_UINT64 = 10L
    private const val TYPE_INT64 = 11L
    private const val TYPE_FLOAT64 = 12L

    /** 估算 [file] 对应模型的 KV/token；file 为 null 或解析失败时回退经验值。 */
    fun estimate(file: File?): KvEstimate {
        val layout = file?.let { runCatching { parseLayout(it) }.getOrNull() }
        val bytesPerToken = layout?.let { bytesPerToken(it) }
        return if (layout != null && bytesPerToken != null && bytesPerToken > 0) {
            KvEstimate(bytesPerToken, KvEstimateSource.GGUF_METADATA, layout)
        } else {
            KvEstimate(FALLBACK_BYTES_PER_TOKEN, KvEstimateSource.FALLBACK)
        }
    }

    /** 指定 token 数下的 KV 字节数；[nCtx] 为负按 0 处理。 */
    fun bytesForTokens(bytesPerToken: Long, nCtx: Int): Long =
        bytesPerToken * nCtx.coerceAtLeast(0).toLong()

    /** 按解析结构计算 KV/token（f16）：Σ 每层 kv_heads × (key_dim + value_dim) × 2B。 */
    internal fun bytesPerToken(layout: GgufKvLayout): Long? {
        if (layout.blockCount <= 0 || layout.headDim <= 0 || layout.valueDim <= 0) return null
        if (layout.headsPerLayer.size != layout.blockCount) return null
        val heads = layout.headsPerLayer.sumOf { it.toLong() }
        if (heads <= 0) return null
        return heads * (layout.headDim.toLong() + layout.valueDim) * 2L
    }

    // ---- 解析 ----

    private fun parseLayout(file: File): GgufKvLayout? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            FileInputStream(file).use { raw ->
                BufferedInputStream(raw, 64 * 1024).use { buffered ->
                    parseHeader(DataInputStream(buffered))
                }
            }
        } catch (error: EOFException) {
            null
        } catch (error: IOException) {
            null
        } catch (error: RuntimeException) {
            null
        }
    }

    private fun parseHeader(input: DataInputStream): GgufKvLayout? {
        if (readU32(input) != GGUF_MAGIC) return null
        val version = readU32(input) ?: return null
        if (version !in VERSION_MIN..VERSION_MAX) return null
        // tensor_count：不关心 tensor 数据，仅按格式占位读取。
        readU64(input) ?: return null
        val kvCount = readU64(input) ?: return null
        if (kvCount <= 0L || kvCount > MAX_KV_COUNT) return null

        val values = HashMap<String, Any>()
        var index = 0L
        while (index < kvCount) {
            val key = readString(input) ?: return null
            val type = readU32(input) ?: return null
            if (isWantedKey(key)) {
                values[key] = readValue(input, type) ?: return null
            } else if (!skipValue(input, type)) {
                return null
            }
            index++
        }
        return buildLayout(values)
    }

    private fun isWantedKey(key: String): Boolean =
        key == KEY_ARCHITECTURE ||
            key.endsWith(SUFFIX_BLOCK_COUNT) ||
            key.endsWith(SUFFIX_HEAD_COUNT) ||
            key.endsWith(SUFFIX_HEAD_COUNT_KV) ||
            key.endsWith(SUFFIX_KEY_LENGTH) ||
            key.endsWith(SUFFIX_VALUE_LENGTH) ||
            key.endsWith(SUFFIX_EMBEDDING_LENGTH)

    private fun buildLayout(values: Map<String, Any>): GgufKvLayout? {
        val architecture = values[KEY_ARCHITECTURE] as? String
        val blockCount = (values.findBySuffix(SUFFIX_BLOCK_COUNT) as? Long)?.toInt() ?: return null
        if (blockCount !in 1..1024) return null

        val rawKvHeads = values.findBySuffix(SUFFIX_HEAD_COUNT_KV) ?: return null
        val headsPerLayer: List<Int> = when (rawKvHeads) {
            is Long -> List(blockCount) { rawKvHeads.toInt() }
            is List<*> -> rawKvHeads.mapNotNull { (it as? Long)?.toInt() }
            else -> return null
        }
        val normalizedHeads = when {
            headsPerLayer.size == blockCount -> headsPerLayer
            headsPerLayer.size == 1 -> List(blockCount) { headsPerLayer[0] }
            else -> return null
        }
        if (normalizedHeads.any { it < 0 }) return null

        val headCount = (values.findBySuffix(SUFFIX_HEAD_COUNT) as? Long)?.toInt()
        val embeddingLength = (values.findBySuffix(SUFFIX_EMBEDDING_LENGTH) as? Long)?.toInt()
        val keyLength = (values.findBySuffix(SUFFIX_KEY_LENGTH) as? Long)?.toInt()
        val valueLength = (values.findBySuffix(SUFFIX_VALUE_LENGTH) as? Long)?.toInt()

        val headDim = when {
            keyLength != null && keyLength > 0 -> keyLength
            headCount != null && headCount > 0 &&
                embeddingLength != null && embeddingLength > 0 &&
                embeddingLength % headCount == 0 -> embeddingLength / headCount
            else -> return null
        }
        val valueDim = valueLength?.takeIf { it > 0 } ?: headDim
        if (headDim !in 1..8192 || valueDim !in 1..8192) return null

        return GgufKvLayout(
            architecture = architecture,
            blockCount = blockCount,
            headDim = headDim,
            valueDim = valueDim,
            headsPerLayer = normalizedHeads,
        )
    }

    private fun Map<String, Any>.findBySuffix(suffix: String): Any? =
        entries.firstOrNull { it.key.endsWith(suffix) }?.value

    // ---- 基础读取（GGUF 为小端序） ----

    private fun readU32(input: DataInputStream): Long? = try {
        java.lang.Integer.toUnsignedLong(java.lang.Integer.reverseBytes(input.readInt()))
    } catch (error: EOFException) {
        null
    }

    private fun readU64(input: DataInputStream): Long? = try {
        java.lang.Long.reverseBytes(input.readLong())
    } catch (error: EOFException) {
        null
    }

    private fun readString(input: DataInputStream): String? {
        val length = readU64(input) ?: return null
        if (length < 0L || length > MAX_STRING_BYTES) return null
        val bytes = ByteArray(length.toInt())
        return try {
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        } catch (error: EOFException) {
            null
        }
    }

    private fun readValue(input: DataInputStream, type: Long): Any? = when (type) {
        TYPE_UINT8 -> input.readUnsignedByte().toLong()
        TYPE_INT8 -> input.readByte().toLong()
        TYPE_UINT16 -> input.readUnsignedShort().toLong()
        TYPE_INT16 -> input.readShort().toLong()
        TYPE_UINT32 -> readU32(input)
        TYPE_INT32 -> input.readInt().toLong()
        TYPE_FLOAT32 -> input.readFloat().toDouble()
        TYPE_BOOL -> input.readUnsignedByte() != 0
        TYPE_STRING -> readString(input)
        TYPE_ARRAY -> readArray(input)
        TYPE_UINT64 -> readU64(input)
        TYPE_INT64 -> input.readLong()
        TYPE_FLOAT64 -> input.readDouble()
        else -> null
    }

    private fun readArray(input: DataInputStream): List<Any>? {
        val elementType = readU32(input) ?: return null
        val count = readU64(input) ?: return null
        if (count < 0L || count > MAX_ARRAY_COUNT) return null
        val values = ArrayList<Any>(minOf(count, 1024L).toInt())
        var index = 0L
        while (index < count) {
            values += readValue(input, elementType) ?: return null
            index++
        }
        return values
    }

    private fun skipValue(input: DataInputStream, type: Long): Boolean = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skip(input, 1L)
        TYPE_UINT16, TYPE_INT16 -> skip(input, 2L)
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skip(input, 4L)
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skip(input, 8L)
        TYPE_STRING -> readString(input) != null
        TYPE_ARRAY -> {
            val elementType = readU32(input) ?: return false
            val count = readU64(input) ?: return false
            if (count < 0L || count > MAX_ARRAY_COUNT) {
                false
            } else {
                var index = 0L
                var ok = true
                while (index < count && ok) {
                    ok = skipValue(input, elementType)
                    index++
                }
                ok
            }
        }
        else -> false
    }

    private fun skip(input: DataInputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skipBytes(minOf(remaining, 8192L).toInt())
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private const val KEY_ARCHITECTURE = "general.architecture"
    private const val SUFFIX_BLOCK_COUNT = ".block_count"
    private const val SUFFIX_HEAD_COUNT = ".attention.head_count"
    private const val SUFFIX_HEAD_COUNT_KV = ".attention.head_count_kv"
    private const val SUFFIX_KEY_LENGTH = ".attention.key_length"
    private const val SUFFIX_VALUE_LENGTH = ".attention.value_length"
    private const val SUFFIX_EMBEDDING_LENGTH = ".embedding_length"
}
