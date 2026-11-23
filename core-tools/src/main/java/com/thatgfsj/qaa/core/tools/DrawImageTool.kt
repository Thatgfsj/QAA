package com.thatgfsj.qaa.core.tools

import com.thatgfsj.qaa.core.inference.IllegalTransitionException
import com.thatgfsj.qaa.core.inference.ImageGenCycleOutcome
import com.thatgfsj.qaa.core.inference.ModelMemoryCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * draw_image 工具（docs/design/harness-core-design.md §3.3 / §9.5）。
 *
 * 内存互斥接线：
 * - 引擎或协调器任一缺席（含全空）→ 预检失败 FAILURE(E_ENGINE_NOT_AVAILABLE)，
 *   不触发确认、不触碰状态机；
 * - 两者就位 → 经 [ModelMemoryCoordinator.requestImageGenCycle] 同步驱动
 *   LLM 卸载 → 画图加载/出图落盘 → 画图卸载 → LLM 重载六段互斥链；
 *   成功返回 SUCCESS（dataJson 含图片路径与摘要），任一 guard/非法迁移失败
 *   返回 FAILURE(E_MEMORY_MUTEX)，协调器已走图外销毁重建回到可恢复态。
 *
 * @param engine 画图引擎；null 表示底层未接入
 * @param coordinator 内存互斥状态机；null 表示宿主未装配互斥链
 */
class DrawImageTool(
    private val engine: ImageGenEngine? = null,
    private val coordinator: ModelMemoryCoordinator? = null,
) : ToolExecutor {

    override val name: String = NAME
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argsJson: String, context: ToolContext): ToolResult {
        val start = System.nanoTime()
        val prompt: String
        val requestedPath: String?
        try {
            val obj = ToolArgs.parseObject(argsJson)
            prompt = ToolArgs.requireString(obj, "prompt")
            requestedPath = ToolArgs.optionalString(obj, "outputPath")
            if (requestedPath != null && requestedPath.isEmpty()) {
                throw ToolArgsException("字段 outputPath 不能为空串（缺省请在沙盒 images/ 下自动命名）")
            }
        } catch (e: ToolArgsException) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, e.message ?: "参数无效", elapsedMs(start))
        }
        if (prompt.isBlank()) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, "字段 prompt 不能为空", elapsedMs(start))
        }
        // 预检：引擎与协调器必须同时装配，否则不进入互斥链（不触发确认、零迁移）。
        if (engine == null || coordinator == null) {
            val reason = if (engine == null) {
                "画图引擎尚未接入，draw_image 暂不可用"
            } else {
                "内存互斥协调器尚未装配，draw_image 暂不可用"
            }
            return ToolResult.failure(ToolErrorCode.ENGINE_NOT_AVAILABLE, reason, elapsedMs(start))
        }
        val outputPath = requestedPath ?: defaultOutputPath(context)
        val resolved = try {
            context.sandbox.resolve(outputPath)
        } catch (e: SandboxPathException) {
            return ToolResult.failure(ToolErrorCode.PATH_ESCAPE, "输出路径越界: " + e.message, elapsedMs(start))
        } catch (e: Exception) {
            return ToolResult.failure(
                ToolErrorCode.IO,
                "输出路径解析失败: " + (e.message ?: e.javaClass.simpleName),
                elapsedMs(start),
            )
        }
        val outcome = try {
            coordinator.requestImageGenCycle(
                prompt = prompt,
                outputPath = outputPath,
                enginePath = resolved.absolutePath.toString(),
            )
        } catch (e: IllegalTransitionException) {
            return ToolResult.failure(
                ToolErrorCode.MEMORY_MUTEX,
                "内存互斥状态机拒绝迁移（当前 " + e.currentState + "，请求 " + e.requestedState + "）",
                elapsedMs(start),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.failure(
                ToolErrorCode.MEMORY_MUTEX,
                "内存互斥周期异常: " + (e.message ?: e.javaClass.simpleName),
                elapsedMs(start),
            )
        }
        return when (outcome) {
            is ImageGenCycleOutcome.Success -> ToolResult.success(
                output = formatSuccess(outcome),
                dataJson = buildJsonObject {
                    put("path", outcome.outputPath)
                    put("summary", outcome.summary)
                    put("state", outcome.state.name)
                }.toString(),
                durationMs = elapsedMs(start),
            )
            is ImageGenCycleOutcome.Failed -> ToolResult.failure(
                ToolErrorCode.MEMORY_MUTEX,
                "内存互斥周期失败（" + outcome.from + " -> " + outcome.requested +
                    "，尝试 " + outcome.attempts + " 次）: " + outcome.reason +
                    "；恢复结果: " + outcome.recovery,
                elapsedMs(start),
            )
        }
    }

    private fun defaultOutputPath(context: ToolContext): String =
        "images/draw_" + System.currentTimeMillis() + "_" + context.stepIndex + ".png"

    private fun formatSuccess(outcome: ImageGenCycleOutcome.Success): String {
        val summary = outcome.summary
        return if (summary.isBlank()) {
            "图片已生成: " + outcome.outputPath
        } else {
            "图片已生成: " + outcome.outputPath + "（" + summary + "）"
        }
    }

    companion object {
        const val NAME = "draw_image"
    }
}
