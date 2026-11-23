package com.thatgfsj.qaa.core.tools

/**
 * draw_image 占位实现（docs/design/harness-core-design.md §3.3）。
 *
 * 画图引擎接入前（engine == null）预检即失败：FAILURE(E_ENGINE_NOT_AVAILABLE)，
 * 不触发确认挂起、不触碰 LLM / 画图内存互斥状态机（装配方负责在触发确认前预检）。
 * 引擎接入后：Allow 确认 → 互斥链出图落盘至沙盒（由内存协调器联动，另行实现）。
 *
 * @param engine 画图引擎；本阶段恒为 null，字段为后续装配预留的接口形状。
 */
class DrawImageTool(private val engine: ImageGenEngine? = null) : ToolExecutor {

    override val name: String = NAME
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argsJson: String, context: ToolContext): ToolResult {
        val start = System.nanoTime()
        val prompt: String
        val outputPath: String?
        try {
            val obj = ToolArgs.parseObject(argsJson)
            prompt = ToolArgs.requireString(obj, "prompt")
            outputPath = ToolArgs.optionalString(obj, "outputPath")
            if (outputPath != null && outputPath.isEmpty()) {
                throw ToolArgsException("字段 outputPath 不能为空串（缺省请在沙盒 images/ 下自动命名）")
            }
        } catch (e: ToolArgsException) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, e.message ?: "参数无效", elapsedMs(start))
        }
        // 预检：引擎缺席直接失败，prompt 校验通过即返回（不进入出图链路）。
        if (engine == null) {
            return ToolResult.failure(
                ToolErrorCode.ENGINE_NOT_AVAILABLE,
                "画图引擎尚未接入，draw_image 暂不可用",
                elapsedMs(start),
            )
        }
        // 引擎已装配但出图链路（内存互斥状态机 + 落盘）未实现。
        return ToolResult.failure(
            ToolErrorCode.ENGINE_NOT_AVAILABLE,
            "画图引擎已装配，但出图链路未实现（prompt=" + prompt.take(64) + "）",
            elapsedMs(start),
        )
    }

    companion object {
        const val NAME = "draw_image"
    }
}
