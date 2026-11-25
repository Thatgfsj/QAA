package com.thatgfsj.qaa.core.inference

/**
 * 张量角色：判定只看 matmul 权重，embedding / norm 不参与。
 *
 * #97 取证：可用的 Q4_0 模型（如 Spark-X2.6-4B-Q4_0）里 `token_embd.weight` 是 Q6_K，
 * 若把整文件张量一起统计就会误杀，所以必须按角色取子集。
 */
enum class GgufTensorRole {
    MATMUL_WEIGHT,
    EMBEDDING,
    NORM,
    OTHER,
}

/** 单个张量元信息（张量目录项，不含张量数据）。 */
data class GgufTensorInfo(
    val name: String,
    val type: GgufQuantization,
    val dims: List<Long>,
) {

    val elementCount: Long
        get() = dims.fold(1L) { acc, dim -> acc * dim.coerceAtLeast(0L) }

    val byteSize: Long get() = GgufTensorSizes.bytesOf(type, elementCount)

    val role: GgufTensorRole get() = GgufTensorRoles.roleOf(name, dims)
}

/** 张量角色判定规则（只看 `blk.N.attn_*` / `blk.N.ffn_*` 的二维以上 `.weight`）。 */
object GgufTensorRoles {

    private val LAYER_WEIGHT = Regex("""^blk\.[0-9]+\.(attn|ffn)_[A-Za-z0-9_.]+\.weight$""")

    fun roleOf(name: String, dims: List<Long>): GgufTensorRole = when {
        name.endsWith("norm.weight") -> GgufTensorRole.NORM
        name == "token_embd.weight" || name.startsWith("token_embd.") -> GgufTensorRole.EMBEDDING
        name == "output.weight" || name.startsWith("output.") -> GgufTensorRole.OTHER
        dims.size >= 2 && LAYER_WEIGHT.matches(name) -> GgufTensorRole.MATMUL_WEIGHT
        else -> GgufTensorRole.OTHER
    }
}

/** ggml 块大小表：按类型把元素数换算成字节（只看数量级，未知类型按 2 字节/元素估计）。 */
object GgufTensorSizes {

    fun bytesOf(type: GgufQuantization, elements: Long): Long {
        if (elements <= 0L) return 0L
        val block = when (type) {
            GgufQuantization.F32 -> 4L to 4L
            GgufQuantization.F16, GgufQuantization.BF16 -> 1L to 2L
            GgufQuantization.Q4_0, GgufQuantization.IQ4_NL -> 32L to 18L
            GgufQuantization.Q4_1 -> 32L to 20L
            GgufQuantization.Q5_0 -> 32L to 22L
            GgufQuantization.Q5_1 -> 32L to 24L
            GgufQuantization.Q8_0 -> 32L to 34L
            GgufQuantization.Q8_1 -> 32L to 36L
            GgufQuantization.Q2_K -> 256L to 84L
            GgufQuantization.Q3_K, GgufQuantization.Q3_K_S, GgufQuantization.Q3_K_M,
            GgufQuantization.Q3_K_L -> 256L to 110L
            GgufQuantization.Q4_K, GgufQuantization.Q4_K_S, GgufQuantization.Q4_K_M -> 256L to 144L
            GgufQuantization.Q5_K, GgufQuantization.Q5_K_S, GgufQuantization.Q5_K_M -> 256L to 176L
            GgufQuantization.Q6_K -> 256L to 210L
            GgufQuantization.Q8_K -> 256L to 292L
            GgufQuantization.IQ4_XS -> 256L to 136L
            GgufQuantization.MXFP4 -> 32L to 17L
            else -> 1L to 2L
        }
        val blockElements = block.first
        val blockBytes = block.second
        return ((elements + blockElements - 1L) / blockElements) * blockBytes
    }
}

/**
 * 按张量角色聚合的量化直方图（#102 判定依据）。
 *
 * 背景（#97 实测）：`general.file_type` 与文件名的量化标记都不可信——
 * Supra2.5-Pro-Instruct-Q4_K_M 声明 `file_type=15`（Q4_K_M），实际张量是 Q5_0/Q8_0/F32 混合；
 * 因此判定只认「attn/ffn matmul 权重」的实测类型分布，声明值仅用于提示不一致。
 */
data class GgufTensorStats(
    val tensorCount: Int = 0,
    val weightTensorCount: Int = 0,
    val weightBytes: Map<GgufQuantization, Long> = emptyMap(),
    val allTypeCounts: Map<GgufQuantization, Int> = emptyMap(),
) {

    val hasWeights: Boolean get() = weightTensorCount > 0 && weightBytesTotal > 0L

    val weightBytesTotal: Long get() = weightBytes.values.sum()

    val dominantWeightType: GgufQuantization?
        get() = weightBytes.maxByOrNull { it.value }?.key

    fun weightShare(type: GgufQuantization): Double {
        val total = weightBytesTotal
        if (total <= 0L) return 0.0
        return (weightBytes[type] ?: 0L).toDouble() / total.toDouble()
    }

    fun weightPercent(type: GgufQuantization): Int = (weightShare(type) * 100.0).toInt()

    /** 证据摘要：`Q4_0×84 / Q8_0×1`（按字节降序，最多 4 项）。 */
    fun histogramText(limit: Int = 4): String =
        weightBytes.entries.sortedByDescending { it.value }.take(limit)
            .joinToString(" / ") { it.key.label + "×" + it.value }

    /** 百分比直方图：`Q4_0×60% / Q8_0×40%`（按字节降序，最多 [limit] 项）。 */
    fun weightPercentHistogram(limit: Int = 3): String =
        weightBytes.entries.sortedByDescending { it.value }.take(limit)
            .joinToString(" / ") { it.key.label + "×" + weightPercent(it.key) + "%" }

    companion object {

        fun of(tensors: List<GgufTensorInfo>): GgufTensorStats {
            if (tensors.isEmpty()) return GgufTensorStats()
            val weightBytes = mutableMapOf<GgufQuantization, Long>()
            val allCounts = mutableMapOf<GgufQuantization, Int>()
            var weightCount = 0
            tensors.forEach { tensor ->
                allCounts[tensor.type] = (allCounts[tensor.type] ?: 0) + 1
                if (tensor.role == GgufTensorRole.MATMUL_WEIGHT) {
                    weightCount++
                    weightBytes[tensor.type] = (weightBytes[tensor.type] ?: 0L) + tensor.byteSize
                }
            }
            return GgufTensorStats(
                tensorCount = tensors.size,
                weightTensorCount = weightCount,
                weightBytes = weightBytes,
                allTypeCounts = allCounts,
            )
        }
    }
}
