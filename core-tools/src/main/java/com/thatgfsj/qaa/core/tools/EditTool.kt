package com.thatgfsj.qaa.core.tools

/**
 * edit 工具（docs/design/harness-core-design.md §3.3）。
 *
 * 精确字符串替换（literal，非正则）：requiresConfirmation = true。
 * 目标必须已存在（不隐式创建）；oldText 须唯一匹配或显式 replaceAll；
 * 整文件重写应改用 write。
 */
class EditTool : ToolExecutor {

    override val name: String = NAME
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argsJson: String, context: ToolContext): ToolResult {
        val start = System.nanoTime()
        val path: String
        val oldText: String
        val newText: String
        val replaceAll: Boolean
        try {
            val obj = ToolArgs.parseObject(argsJson)
            path = ToolArgs.requireString(obj, "path")
            oldText = ToolArgs.requireString(obj, "oldText")
            newText = ToolArgs.requireString(obj, "newText")
            replaceAll = ToolArgs.optionalBoolean(obj, "replaceAll", default = false)
        } catch (e: ToolArgsException) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, e.message ?: "参数无效", elapsedMs(start))
        }
        if (oldText.isEmpty()) {
            return ToolResult.failure(
                ToolErrorCode.EMPTY_OLD_TEXT,
                "oldText 不能为空串；如需清空或整写文件请改用 write",
                elapsedMs(start),
            )
        }
        val sandbox = context.sandbox
        return try {
            when (val outcome = sandbox.applyEdit(path, oldText, newText, replaceAll)) {
                is EditOutcome.Applied -> ToolResult.success(
                    "已替换 " + outcome.replacements + " 处: " + path +
                        "（新内容 " + outcome.newLength + " 字符）",
                    durationMs = elapsedMs(start),
                )
                is EditOutcome.NotFound -> ToolResult.failure(
                    ToolErrorCode.NOT_FOUND,
                    notFoundOutput(path, outcome.nearestHint),
                    elapsedMs(start),
                )
                is EditOutcome.MultiMatch -> ToolResult.failure(
                    ToolErrorCode.MULTI_MATCH,
                    "oldText 出现 " + outcome.matches + " 处且 replaceAll=false；" +
                        "请提供更长上下文使匹配唯一，或显式传 replaceAll=true",
                    elapsedMs(start),
                )
            }
        } catch (e: SandboxPathException) {
            ToolResult.failure(ToolErrorCode.PATH_ESCAPE, "路径越界: " + e.message, elapsedMs(start))
        } catch (e: SandboxFileNotFoundException) {
            ToolResult.failure(
                ToolErrorCode.NOT_FOUND,
                "目标文件不存在（edit 不会隐式创建）: " + path,
                elapsedMs(start),
            )
        } catch (e: SandboxIoException) {
            ToolResult.failure(ToolErrorCode.IO, "替换失败: " + (e.message ?: "IO 错误"), elapsedMs(start))
        } catch (e: Exception) {
            ToolResult.failure(ToolErrorCode.IO, "替换异常: " + (e.message ?: e.javaClass.simpleName), elapsedMs(start))
        }
    }

    private fun notFoundOutput(path: String, nearestHint: String?): String {
        val head = "未在 " + path + " 中找到待替换片段"
        return if (nearestHint != null) {
            head + "。" + nearestHint + "。请先 read 核对后重试"
        } else {
            head + "，且无相似片段。请先 read 查看当前内容"
        }
    }

    companion object {
        const val NAME = "edit"
    }
}
