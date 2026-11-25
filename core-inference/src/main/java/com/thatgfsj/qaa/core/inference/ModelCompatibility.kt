package com.thatgfsj.qaa.core.inference

import java.io.File

/** 支持结论：只有主导 Q4_0 权重的模型可用（GenieX 0.10 现场取证见 #97）。 */
enum class SupportStatus(val displayName: String) {
    SUPPORTED("受支持"),
    BLOCK_KQUANT("不可用（K 系列权重）"),
    QUARANTINE_Q8("不可用（非 Q4_0 量化）"),
    UNKNOWN("未知档位");

    /** 导入 / 下载 / 清单是否放行：[UNKNOWN] 放行但必须带提示（保留下载中断、非标准 GGUF 的排查能力）。 */
    val allowed: Boolean get() = this == SUPPORTED || this == UNKNOWN

    /** 是否允许载入推理：只有 [SUPPORTED]；未知档位一律拒绝载入（保守拦截）。 */
    val loadAllowed: Boolean get() = this == SUPPORTED
}

/**
 * 单个模型的兼容性结论。
 *
 * @param quantization 判定依据：读到张量目录时是「attn/ffn 权重主类型」，否则回退声明档位
 * @param declaredQuantization 头里 `general.file_type` 或文件名声明的档位（只作提示，不作判定依据）
 * @param weightSharePercent 权重主类型按字节占比（读到张量目录时有值）
 * @param weightTensorCount 参与判定的 matmul 权重张量数
 * @param fileTypeMismatch 声明档位与实测权重主类型不一致（如声明 Q4_K_M 实为 Q5_0）
 * @param headerRead 是否成功读到文件头（false 时结论来自文件名或缺失，仅作提示）
 */
data class ModelCompatibility(
    val status: SupportStatus,
    val quantization: GgufQuantization,
    val architecture: String? = null,
    val headerRead: Boolean = false,
    val declaredQuantization: GgufQuantization? = null,
    val weightSharePercent: Int? = null,
    val weightTensorCount: Int = 0,
    val fileTypeMismatch: Boolean = false,
    /** 证据摘要，如 `Q5_0 占 87%（attn/ffn 权重）`。 */
    val weightDetail: String? = null,
) {

    val quantizationLabel: String get() = quantization.label

    val supported: Boolean get() = status == SupportStatus.SUPPORTED

    /** 允许但需要提示的说明（不受支持 → 风险说明；未知 → 放行警告；Q4_0 → null）。 */
    val displayNote: String?
        get() = when (status) {
            SupportStatus.BLOCK_KQUANT, SupportStatus.QUARANTINE_Q8 -> buildString {
                append(ModelCompatibilityGate.UNSUPPORTED_NOTE)
                weightDetail?.let {
                    append("（")
                    append(it)
                    append("）")
                }
                if (fileTypeMismatch) {
                    append("，声明 ")
                    append(declaredQuantization?.label ?: "未知")
                    append(" 与实际张量不符")
                }
            }
            SupportStatus.UNKNOWN -> {
                val base = ModelCompatibilityGate.UNKNOWN_NOTE
                if (weightDetail == null) base else base + "（" + weightDetail + "）"
            }
            SupportStatus.SUPPORTED -> null
        }
}

/**
 * 模型兼容门禁：集中维护「按张量角色判定的支持矩阵」与中文文案。
 *
 * 判定口径（#97 / #117 取证，见 `docs/research/evidence/gguf-headers.md`）：
 * 1. 不看文件名、不看 `general.file_type`：Supra2.5-Pro-Instruct-Q4_K_M 声明 15（Q4_K_M）
 *    但实测张量是 Q5_0/Q8_0/F32 混合，声明值不可信。
 * 2. 不按「含 K 块就拦」：可用的 Spark-X2.6-4B-Q4_0 里 `token_embd.weight` 是 Q6_K。
 * 3. 只看 `blk.N.attn_*` / `blk.N.ffn_*` 的二维以上 `.weight`（embedding / norm 不参与），
 *    按张量类型直方图取主类型：Q4_0 权重占比 ≥ 95% → 受支持；其余已知类型 → 不受支持。
 * 4. 读不到张量目录（截断 / 无类型信息）→ 未知档位放行，但必须提示风险，绝不静默当可用。
 */
object ModelCompatibilityGate {

    /** 唯一受支持的量化档位（matmul 权重）。 */
    const val SUPPORTED_LABEL = "Q4_0"

    /** 受支持判定阈值：Q4_0 权重按字节占比。 */
    const val SUPPORTED_WEIGHT_SHARE = 95

    const val UNSUPPORTED_NOTE = "【不受支持（GenieX 0.10 会乱码/崩溃）】"
    const val UNKNOWN_NOTE = "【未识别的量化档位：导入/下载放行，但无法确认兼容性，载入会被拒绝；可能乱码或崩溃】"

    /** 未知档位在载入点一律拒绝（导入 / 下载仍放行，避免打断下载中断等排查）。 */
    const val UNKNOWN_LOAD_REJECTION =
        "无法确认兼容性：文件头或张量信息不完整（疑似下载未完成或非标准 GGUF），可能乱码或崩溃，已拒绝载入；请重新下载或改用 Q4_0 模型。"

    private const val REASON = "GenieX 0.10 实测仅在 Q4_0 权重上可用，该档位会输出乱码或原生崩溃（#97 取证）"
    private const val ADVICE = "请改用 Q4_0 量化版本（attn/ffn 权重为 Q4_0 的 GGUF）"

    /** 拦截点：导入 / 下载 / 载入，文案尾部按动作区分。 */
    enum class ModelAction(val displayName: String, val suffix: String) {
        IMPORT("导入", "，已删除该文件"),
        PULL("下载", "，已中止下载"),
        LOAD("载入", "，请重新选择 Q4_0 模型（输入 model 查看清单）"),
    }

    /** 头解析成功 → 结论（按张量直方图判定）。 */
    fun assess(header: GgufHeader): ModelCompatibility {
        val declared = header.quantization
        val stats = header.tensorStats
        if (!stats.hasWeights) {
            // 头可读但没有可用的权重张量信息：不能按档位结论放行，降级为未知档位 + 提示。
            return ModelCompatibility(
                status = SupportStatus.UNKNOWN,
                quantization = declared,
                architecture = header.architecture,
                headerRead = true,
                declaredQuantization = declared,
            )
        }
        val dominant = stats.dominantWeightType ?: GgufQuantization.UNKNOWN
        val dominantPercent = stats.weightPercent(dominant)
        val q4oPercent = stats.weightPercent(GgufQuantization.Q4_0)
        val mismatch = declared.known && dominant.known && declared != dominant
        val status = when {
            !dominant.known -> SupportStatus.UNKNOWN
            dominant == GgufQuantization.Q4_0 && q4oPercent >= SUPPORTED_WEIGHT_SHARE ->
                SupportStatus.SUPPORTED
            dominant.isKSeries -> SupportStatus.BLOCK_KQUANT
            else -> SupportStatus.QUARANTINE_Q8
        }
        return ModelCompatibility(
            status = status,
            quantization = if (dominant.known) dominant else GgufQuantization.UNKNOWN,
            architecture = header.architecture,
            headerRead = true,
            declaredQuantization = declared,
            weightSharePercent = dominantPercent,
            weightTensorCount = stats.weightTensorCount,
            fileTypeMismatch = mismatch,
            weightDetail = dominant.label + " 占 " + dominantPercent + "%（attn/ffn 权重；" +
                stats.weightPercentHistogram() + "）",
        )
    }

    /** 声明档位 → 结论（仅文件名预检等拿不到张量信息时使用）。 */
    fun assess(
        quantization: GgufQuantization,
        architecture: String? = null,
        headerRead: Boolean = false,
    ): ModelCompatibility {
        val status = when {
            quantization == GgufQuantization.Q4_0 -> SupportStatus.SUPPORTED
            quantization == GgufQuantization.UNKNOWN -> SupportStatus.UNKNOWN
            quantization.isKSeries -> SupportStatus.BLOCK_KQUANT
            else -> SupportStatus.QUARANTINE_Q8
        }
        return ModelCompatibility(
            status = status,
            quantization = quantization,
            architecture = architecture,
            headerRead = headerRead,
            declaredQuantization = quantization,
        )
    }

    /** 读文件头评估（导入 / 载入 / 清单展示用）。 */
    fun assessFile(file: File?): ModelCompatibility =
        when (val result = GgufHeaderReader.read(file)) {
            is GgufHeaderResult.Parsed -> assess(result.header)
            is GgufHeaderResult.Unreadable -> ModelCompatibility(
                SupportStatus.UNKNOWN,
                GgufQuantization.UNKNOWN,
                architecture = null,
                headerRead = false,
            )
        }

    /**
     * 文件名启发式评估（仅下载前预检用：此时还没有文件可读头）。
     *
     * 文件名同样不可信（声明 Q4_K_M 的文件实测可能是 Q5_0 张量），
     * 因此这里只用于「明显非 Q4_0 的早退」，下载完成后仍以张量直方图为准。
     */
    fun assessFileName(fileName: String?): ModelCompatibility {
        val label = quantizationOf(fileName)
        val quantization = GgufQuantization.fromLabel(label) ?: GgufQuantization.UNKNOWN
        return assess(quantization, architecture = null, headerRead = false)
    }

    /** 下载前预检：返回拒绝文案（null 表示可继续）。 */
    fun rejectionMessageForName(
        fileName: String?,
        modelLabel: String,
        action: ModelAction = ModelAction.PULL,
    ): String? = rejectionMessage(action, modelLabel, assessFileName(fileName))

    /**
     * 拦截文案：null 表示放行。
     *
     * 放行口径分场景：导入 / 下载 / 清单按 [SupportStatus.allowed]（未知放行 + 提示）；
     * 载入按 [SupportStatus.loadAllowed]（未知一律拒绝，文案用 [UNKNOWN_LOAD_REJECTION]）。
     */
    fun rejectionMessage(
        action: ModelAction,
        modelLabel: String,
        compatibility: ModelCompatibility,
    ): String? {
        val blocked = if (action == ModelAction.LOAD) {
            !compatibility.status.loadAllowed
        } else {
            !compatibility.status.allowed
        }
        if (!blocked) return null
        if (action == ModelAction.LOAD && compatibility.status == SupportStatus.UNKNOWN) {
            return UNKNOWN_LOAD_REJECTION
        }
        return buildString {
            append("模型")
            append(action.displayName)
            append("被拒绝：")
            append(modelLabel.ifBlank { "该模型" })
            append(" 的 ")
            append(compatibility.quantizationLabel)
            append(" 权重不受支持（")
            append(dispositionLabel(compatibility.status))
            append("），")
            append(reasonText(compatibility))
            append("；")
            append(ADVICE)
            append(action.suffix)
            append("。")
        }
    }

    /** 处置标签：文案要能区分 BLOCK_KQUANT / QUARANTINE_Q8 / UNKNOWN。 */
    private fun dispositionLabel(status: SupportStatus): String = when (status) {
        SupportStatus.BLOCK_KQUANT -> "K 系列量化块 BLOCK_KQUANT"
        SupportStatus.QUARANTINE_Q8 -> "非 Q4_0 量化 QUARANTINE_Q8"
        SupportStatus.UNKNOWN -> "未知档位 UNKNOWN"
        SupportStatus.SUPPORTED -> "受支持"
    }

    private fun reasonText(compatibility: ModelCompatibility): String {
        val share = compatibility.weightSharePercent
        if (share == null) return REASON
        val detail = compatibility.weightDetail ?: (compatibility.quantizationLabel + " 占 " + share + "%")
        val text = "GenieX 0.10 实测仅在 Q4_0 权重上可用，该模型 attn/ffn 权重为 " + detail +
            "，属于 #97 记录的乱码/崩溃档位"
        if (!compatibility.fileTypeMismatch) return text
        return text + "；文件声明为 " + (compatibility.declaredQuantization?.label ?: "未知") +
            "，与实际张量不符，声明值不可信"
    }

    /** 放行时的提示（未知档位）；受支持档位返回 null。 */
    fun hintText(modelLabel: String, compatibility: ModelCompatibility): String? {
        if (compatibility.status != SupportStatus.UNKNOWN) return null
        val label = modelLabel.ifBlank { "该模型" }
        val detail = compatibility.weightDetail
        return if (compatibility.headerRead && detail != null) {
            "提示：" + label + " 的权重类型无法确定（" + detail + "），已放行；" + ADVICE + "；若出现乱码或崩溃请及时更换。"
        } else {
            "提示：" + label + " 的量化档位无法识别（可能头信息缺失或文件不完整），已放行；" +
                ADVICE + "；若出现乱码或崩溃请及时更换。"
        }
    }

    /** 清单展示用标签：受支持档位无后缀，其余追加状态说明。 */
    fun displayNote(compatibility: ModelCompatibility?): String? = compatibility?.displayNote
}

/** K 系列量化块（Q2_K…Q8_K）：matmul 反量化路径不兼容 → BLOCK_KQUANT。 */
private val GgufQuantization.isKSeries: Boolean
    get() = label.contains("_K")
