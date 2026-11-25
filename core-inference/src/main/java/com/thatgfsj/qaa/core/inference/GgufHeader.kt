package com.thatgfsj.qaa.core.inference

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * GGUF 量化档位（`general.file_type` 的 ggml_ftype 投影）。
 *
 * 仅做格式层映射，不含支持结论；支持矩阵见 ModelCompatibilityGate（core-inference）。
 */
enum class GgufQuantization(val label: String) {
    F32("F32"),
    F16("F16"),
    BF16("BF16"),
    Q4_0("Q4_0"),
    Q4_1("Q4_1"),
    Q5_0("Q5_0"),
    Q5_1("Q5_1"),
    Q8_0("Q8_0"),
    Q8_1("Q8_1"),
    Q2_K("Q2_K"),
    Q2_K_S("Q2_K_S"),
    Q3_K("Q3_K"),
    Q3_K_S("Q3_K_S"),
    Q3_K_M("Q3_K_M"),
    Q3_K_L("Q3_K_L"),
    Q4_K("Q4_K"),
    Q4_K_S("Q4_K_S"),
    Q4_K_M("Q4_K_M"),
    Q5_K("Q5_K"),
    Q5_K_S("Q5_K_S"),
    Q5_K_M("Q5_K_M"),
    Q6_K("Q6_K"),
    Q8_K("Q8_K"),
    IQ1_S("IQ1_S"),
    IQ1_M("IQ1_M"),
    IQ2_XXS("IQ2_XXS"),
    IQ2_XS("IQ2_XS"),
    IQ2_S("IQ2_S"),
    IQ3_XXS("IQ3_XXS"),
    IQ3_XS("IQ3_XS"),
    IQ3_S("IQ3_S"),
    IQ4_NL("IQ4_NL"),
    IQ4_XS("IQ4_XS"),
    TQ1_0("TQ1_0"),
    TQ2_0("TQ2_0"),
    MXFP4("MXFP4"),
    UNKNOWN("未知");

    /** 是否识别出具体档位；[UNKNOWN] 表示元数据缺失或档位过新。 */
    val known: Boolean get() = this != UNKNOWN

    /** GGUF 张量目录里的 `ggml_type` 数值；file_type 专有档位（Q4_K_M 等）为 null。 */
    val tensorTypeId: Long?
        get() = TENSOR_TYPES.entries.firstOrNull { it.value == this }?.key

    companion object {

        /** ggml_ftype 数值 → 档位；未收录（含 -1 UNKNOWN）返回 null。 */
        fun fromFileType(fileType: Long?): GgufQuantization? = when (fileType) {
            0L -> F32
            1L -> F16
            32L -> BF16
            2L -> Q4_0
            3L -> Q4_1
            8L -> Q5_0
            9L -> Q5_1
            7L -> Q8_0
            10L -> Q2_K
            21L -> Q2_K_S
            11L -> Q3_K_S
            12L -> Q3_K_M
            13L -> Q3_K_L
            14L -> Q4_K_S
            15L -> Q4_K_M
            16L -> Q5_K_S
            17L -> Q5_K_M
            18L -> Q6_K
            19L -> IQ2_XXS
            20L -> IQ2_XS
            23L -> IQ3_XXS
            22L -> IQ3_XS
            26L -> IQ3_S
            25L -> IQ4_NL
            30L -> IQ4_XS
            24L -> IQ1_S
            31L -> IQ1_M
            28L -> IQ2_S
            36L -> TQ1_0
            37L -> TQ2_0
            else -> null
        }

        /** GGUF 张量目录的 `ggml_type` → 档位；未收录（如 IQ 新档 / 整型）返回 null。 */
        fun fromTensorType(tensorType: Long?): GgufQuantization? {
            if (tensorType == null) return null
            return TENSOR_TYPES[tensorType]
        }

        /** 文件名 / 仓库名里的量化标记（`Q4_K_M` 等，已大写）→ 档位。 */
        fun fromLabel(text: String?): GgufQuantization? {
            if (text.isNullOrBlank()) return null
            val upper = text.trim().uppercase()
            return values().firstOrNull { it.known && it.label == upper }
        }

        private val TENSOR_TYPES: Map<Long, GgufQuantization> = mapOf(
            0L to F32,
            1L to F16,
            2L to Q4_0,
            3L to Q4_1,
            6L to Q5_0,
            7L to Q5_1,
            8L to Q8_0,
            9L to Q8_1,
            10L to Q2_K,
            11L to Q3_K,
            12L to Q4_K,
            13L to Q5_K,
            14L to Q6_K,
            15L to Q8_K,
            16L to IQ2_XXS,
            17L to IQ2_XS,
            18L to IQ3_XXS,
            19L to IQ1_S,
            20L to IQ4_NL,
            21L to IQ3_S,
            22L to IQ2_S,
            23L to IQ4_XS,
            29L to IQ1_M,
            30L to BF16,
            34L to TQ1_0,
            35L to TQ2_0,
            39L to MXFP4,
        )
    }
}

/**
 * GGUF 头解析结果（纯 JVM，无 Android 依赖，可单测）。
 *
 * @param fileType `general.file_type` 原值；缺失为 null
 * @param headCountKv 每层 KV 头数：标量形态为单元素列表，数组形态为原始数组
 */
data class GgufHeader(
    val version: Long,
    val architecture: String? = null,
    val fileType: Long? = null,
    val blockCount: Int? = null,
    val headCount: Int? = null,
    val headCountKv: List<Int> = emptyList(),
    val embeddingLength: Int? = null,
    val keyLength: Int? = null,
    val valueLength: Int? = null,
    /** 张量目录聚合结果（#102：判定只看 matmul 权重实测类型，不看文件声明）。 */
    val tensorStats: GgufTensorStats = GgufTensorStats(),
) {

    /** 头里声明的量化档位；缺失或版本过新为 [GgufQuantization.UNKNOWN]。 */
    val quantization: GgufQuantization
        get() = GgufQuantization.fromFileType(fileType) ?: GgufQuantization.UNKNOWN

    /** 实测权重主类型；有张量目录时以张量为准（主类型未收录则为未知），只有读不到张量时才回退声明档位。 */
    val weightQuantization: GgufQuantization
        get() = if (tensorStats.hasWeights) {
            tensorStats.dominantWeightType ?: GgufQuantization.UNKNOWN
        } else {
            quantization
        }

    /** 单头维度：优先 `key_length`，否则 embedding_length ÷ head_count（能整除时）。 */
    val headDim: Int?
        get() {
            keyLength?.takeIf { it > 0 }?.let { return it }
            val heads = headCount?.takeIf { it > 0 } ?: return null
            val embedding = embeddingLength?.takeIf { it > 0 } ?: return null
            return if (embedding % heads == 0) embedding / heads else null
        }

    /** 单值维度：优先 `value_length`，否则同 [headDim]（供 KV 估算复用）。 */
    val valueDim: Int? get() = valueLength?.takeIf { it > 0 } ?: headDim

    /** KV 头数（数组形态取最大值；用于摘要展示与 KV 估算上界）。 */
    val kvHeadCount: Int? get() = headCountKv.maxOrNull()

    /** 逐层 KV 头数：标量形态按 [blockCount] 展开，避免下游重复判形态。 */
    val headsPerLayer: List<Int>
        get() {
            if (headCountKv.isEmpty()) return emptyList()
            if (headCountKv.size != 1) return headCountKv
            val layers = blockCount?.takeIf { it in 1..MAX_LAYERS } ?: return headCountKv
            return List(layers) { headCountKv[0] }
        }

    private companion object {
        const val MAX_LAYERS = 512
    }
}

/** 头解析结果：成功给出 [GgufHeader]，失败给出可直接展示的中文原因。 */
sealed interface GgufHeaderResult {

    data class Parsed(
        val header: GgufHeader,
        val tensors: List<GgufTensorInfo> = emptyList(),
    ) : GgufHeaderResult

    /** 不可解析（非 GGUF / 截断 / 版本不支持 / I/O 失败）。 */
    data class Unreadable(val reason: String) : GgufHeaderResult
}

/**
 * GGUF 头解析器：只读 magic / version / metadata KV（不读张量数据）。
 *
 * 兼容 GGUF v2 / v3（0.10.0 现场模型均为 v3）。解析失败不抛异常，统一走
 * [GgufHeaderResult.Unreadable]，让调用方按「未知档位放行 + 提示」处理，
 * 避免历史脏文件把导入 / 载入链路整体打死。
 */
object GgufHeaderReader {

    /** "GGUF" 的小端 u32。 */
    const val GGUF_MAGIC = 0x46554747L

    const val VERSION_MIN = 2L
    const val VERSION_MAX = 3L

    /** metadata 条目数上限（防御异常头）。 */
    const val MAX_KV_COUNT = 1_000_000L

    /** 单个数组 / 字符串上限（避免被坏头拖入超大分配）。 */
    const val MAX_ARRAY_COUNT = 20_000_000L
    const val MAX_STRING_BYTES = 4L * 1024L * 1024L

    /** 张量目录条目上限（防御异常头；正常模型几十到几百条）。 */
    const val MAX_TENSORS = 100_000

    /** 单个张量的维度数上限（ggml 最多 4 维，留余量）。 */
    private const val MAX_TENSOR_DIMS = 8L

    /** 读取 [file] 的头；文件缺失 / 不可读同样返回 [GgufHeaderResult.Unreadable]。 */
    fun read(file: File?): GgufHeaderResult {
        if (file == null || !file.isFile) {
            return GgufHeaderResult.Unreadable("模型文件不存在或不可读")
        }
        return try {
            file.inputStream().buffered().use { parse(it) }
        } catch (error: IOException) {
            GgufHeaderResult.Unreadable("读取 GGUF 头失败：" + (error.message ?: "I/O 错误"))
        }
    }

    /** 解析内存字节（单测夹具用，与文件链路同一实现）。 */
    fun parse(bytes: ByteArray): GgufHeaderResult =
        try {
            bytes.inputStream().buffered().use { parse(it) }
        } catch (error: IOException) {
            GgufHeaderResult.Unreadable("读取 GGUF 头失败：" + (error.message ?: "I/O 错误"))
        }

    /** 流式解析入口；调用方负责关闭流。 */
    fun parse(input: InputStream): GgufHeaderResult {
        val cursor = GgufCursor(input)
        return try {
            parseHeader(cursor)
        } catch (eof: EOFException) {
            GgufHeaderResult.Unreadable("GGUF 头被截断（文件可能不完整）")
        } catch (format: GgufFormatException) {
            GgufHeaderResult.Unreadable(format.message ?: "GGUF 头格式异常")
        } catch (error: IOException) {
            GgufHeaderResult.Unreadable("读取 GGUF 头失败：" + (error.message ?: "I/O 错误"))
        }
    }

    private fun parseHeader(cursor: GgufCursor): GgufHeaderResult {
        val magic = cursor.u32()
        if (magic != GGUF_MAGIC) {
            val hex = magic.toString(16).padStart(8, '0')
            return GgufHeaderResult.Unreadable("不是 GGUF 文件（magic 校验失败：0x" + hex + "）")
        }
        val version = cursor.u32()
        if (version < VERSION_MIN || version > VERSION_MAX) {
            return GgufHeaderResult.Unreadable(
                "不支持的 GGUF 版本：" + version + "（仅支持 " + VERSION_MIN + " / " + VERSION_MAX + "）",
            )
        }
        val tensorCount = cursor.u64()
        if (tensorCount < 0L || tensorCount > MAX_TENSORS) {
            return GgufHeaderResult.Unreadable("GGUF 头异常：张量条目数 " + tensorCount + " 超出上限")
        }
        val kvCount = cursor.u64()
        if (kvCount <= 0L || kvCount > MAX_KV_COUNT) {
            return GgufHeaderResult.Unreadable("GGUF 头异常：metadata 条目数 " + kvCount + " 超出上限")
        }
        var architecture: String? = null
        var fileType: Long? = null
        var blockCount: Int? = null
        var headCount: Int? = null
        var headCountKv: List<Int> = emptyList()
        var embeddingLength: Int? = null
        var keyLength: Int? = null
        var valueLength: Int? = null
        var scanned = 0
        // 必须扫完所有 metadata KV 才能到达张量目录（#102 判定依据在张量类型里）。
        while (scanned < kvCount.toInt()) {
            scanned++
            val key = cursor.readString()
            val type = cursor.u32()
            when (key.substringAfterLast('.')) {
                "architecture" -> architecture = cursor.readStringOrSkip(type) ?: architecture
                "file_type" -> fileType = cursor.readIntegerOrSkip(type) ?: fileType
                "block_count" -> blockCount = cursor.readIntegerOrSkip(type)?.toInt() ?: blockCount
                "head_count" -> headCount = cursor.readIntegerOrSkip(type)?.toInt() ?: headCount
                "head_count_kv" -> headCountKv = cursor.readIntListOrSkip(type) ?: headCountKv
                "embedding_length" -> embeddingLength =
                    cursor.readIntegerOrSkip(type)?.toInt() ?: embeddingLength
                "key_length" -> keyLength = cursor.readIntegerOrSkip(type)?.toInt() ?: keyLength
                "value_length" -> valueLength = cursor.readIntegerOrSkip(type)?.toInt() ?: valueLength
                else -> cursor.skipValue(type)
            }
        }
        val tensors = readTensors(cursor, tensorCount)
        return GgufHeaderResult.Parsed(
            header = GgufHeader(
                version = version,
                architecture = architecture,
                fileType = fileType,
                blockCount = blockCount,
                headCount = headCount,
                headCountKv = headCountKv,
                embeddingLength = embeddingLength,
                keyLength = keyLength,
                valueLength = valueLength,
                tensorStats = GgufTensorStats.of(tensors),
            ),
            tensors = tensors,
        )
    }

    /** 读取张量目录（名称 / 维度 / 类型 / 数据偏移），不读取张量数据。 */
    private fun readTensors(cursor: GgufCursor, count: Long): List<GgufTensorInfo> {
        if (count == 0L) return emptyList()
        return List(count.toInt()) {
            val name = cursor.readString()
            val nDims = cursor.u32()
            if (nDims <= 0L || nDims > MAX_TENSOR_DIMS) {
                throw GgufFormatException(
                    "GGUF 头异常：张量 " + name + " 的维度数 " + nDims + " 超出上限",
                )
            }
            val dims = List(nDims.toInt()) { cursor.u64() }
            val type = GgufQuantization.fromTensorType(cursor.u32()) ?: GgufQuantization.UNKNOWN
            cursor.u64() // 张量数据偏移：判定不需要
            GgufTensorInfo(name = name, type = type, dims = dims)
        }
    }
}

/** 头格式异常（非 EOF、非 I/O）：消息直接面向用户。 */
private class GgufFormatException(message: String) : IOException(message)

/** GGUF 头只读游标：小端读取 + 跳过不定长值。 */
private class GgufCursor(private val input: InputStream) {

    fun u8(): Int {
        readByte()?.let { return it and 0xFF }
        throw EOFException("GGUF 头被截断")
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun i16(): Int = u16().toShort().toInt()

    fun i8(): Int = u8().toByte().toInt()

    fun u32(): Long {
        var value = 0L
        for (shift in 0 until 32 step 8) value = value or (u8().toLong() shl shift)
        return value
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        var value = 0L
        for (shift in 0 until 64 step 8) value = value or (u8().toLong() shl shift)
        return value
    }

    fun readString(): String {
        val length = u64()
        if (length < 0L || length > GgufHeaderReader.MAX_STRING_BYTES) {
            throw GgufFormatException("GGUF 头异常：字符串长度 " + length + " 超出上限")
        }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun readInteger(type: Long): Long = when (type) {
        0L -> u8().toLong()
        1L -> i8().toLong()
        2L -> u16().toLong()
        3L -> i16().toLong()
        4L -> u32()
        5L -> i32().toLong()
        10L -> u64()
        11L -> u64()
        else -> throw GgufFormatException("GGUF 头异常：值类型 " + type + " 不是整数")
    }

    fun readIntegerOrSkip(type: Long): Long? {
        if (type !in INT_TYPES) {
            skipValue(type)
            return null
        }
        return readInteger(type)
    }

    fun readStringOrSkip(type: Long): String? {
        if (type != TYPE_STRING) {
            skipValue(type)
            return null
        }
        return readString()
    }

    /** 标量 → 单元素列表；数组 → 逐元素读取（非整数数组 / 超大数组跳过并返回 null）。 */
    fun readIntListOrSkip(type: Long): List<Int>? {
        if (type != TYPE_ARRAY) {
            val value = readIntegerOrSkip(type) ?: return null
            return listOf(value.toInt())
        }
        val elementType = u32()
        val count = u64()
        checkArrayCount(elementType, count)
        if (elementType !in INT_TYPES || count > MAX_KV_HEAD_ENTRIES) {
            skipArrayElements(elementType, count)
            return null
        }
        return List(count.toInt()) { readInteger(elementType).toInt() }
    }

    /** 跳过任意 metadata 值。 */
    fun skipValue(type: Long) {
        when (type) {
            0L, 1L, 7L -> skipFully(1L)
            2L, 3L -> skipFully(2L)
            4L, 5L, 6L -> skipFully(4L)
            8L -> {
                val length = u64()
                if (length < 0L || length > GgufHeaderReader.MAX_STRING_BYTES) {
                    throw GgufFormatException("GGUF 头异常：字符串长度 " + length + " 超出上限")
                }
                skipFully(length)
            }
            9L -> {
                val elementType = u32()
                val count = u64()
                checkArrayCount(elementType, count)
                skipArrayElements(elementType, count)
            }
            10L, 11L, 12L -> skipFully(8L)
            else -> throw GgufFormatException("GGUF 头异常：未知 metadata 值类型 " + type)
        }
    }

    private fun checkArrayCount(elementType: Long, count: Long) {
        if (count < 0L || count > GgufHeaderReader.MAX_ARRAY_COUNT) {
            throw GgufFormatException("GGUF 头异常：数组长度 " + count + " 超出上限")
        }
        if (elementType == TYPE_ARRAY) {
            throw GgufFormatException("GGUF 头异常：不支持嵌套数组")
        }
        if (elementType > TYPE_FLOAT64) {
            throw GgufFormatException("GGUF 头异常：未知数组元素类型 " + elementType)
        }
    }

    private fun skipArrayElements(elementType: Long, count: Long) {
        when (elementType) {
            0L, 1L, 7L -> skipFully(count)
            2L, 3L -> skipFully(count * 2L)
            4L, 5L, 6L -> skipFully(count * 4L)
            8L -> repeat(count.toInt()) {
                val length = u64()
                if (length < 0L || length > GgufHeaderReader.MAX_STRING_BYTES) {
                    throw GgufFormatException("GGUF 头异常：字符串长度 " + length + " 超出上限")
                }
                skipFully(length)
            }
            10L, 11L, 12L -> skipFully(count * 8L)
            else -> throw GgufFormatException("GGUF 头异常：未知数组元素类型 " + elementType)
        }
    }

    private fun readByte(): Int? = input.read().let { if (it < 0) null else it }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("GGUF 头被截断")
            offset += read
        }
    }

    private fun skipFully(count: Long) {
        var remaining = count
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val step = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, step)
            if (read < 0) throw EOFException("GGUF 头被截断")
            remaining -= read.toLong()
        }
    }

    private companion object {
        const val TYPE_STRING = 8L
        const val TYPE_ARRAY = 9L
        const val TYPE_FLOAT64 = 12L
        const val MAX_KV_HEAD_ENTRIES = 4096L
        val INT_TYPES = setOf(0L, 1L, 2L, 3L, 4L, 5L, 10L, 11L)
    }
}
