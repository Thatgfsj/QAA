package com.thatgfsj.qaa.core.tools

import java.nio.charset.StandardCharsets

/**
 * write 工具（docs/design/harness-core-design.md §3.3）。
 *
 * 整体覆盖语义：requiresConfirmation = true。
 * 目标不存在则连同父目录创建；已存在则全量替换（临时文件 + 原子重命名）。
 * 内容超 limits.write → FAILURE(E_SIZE_LIMIT)。
 */
class WriteTool : ToolExecutor {

    override val name: String = NAME
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argsJson: String, context: ToolContext): ToolResult {
        val start = System.nanoTime()
        val path: String
        val content: String
        try {
            val obj = ToolArgs.parseObject(argsJson)
            path = ToolArgs.requireString(obj, "path")
            content = ToolArgs.requireString(obj, "content")
        } catch (e: ToolArgsException) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, e.message ?: "参数无效", elapsedMs(start))
        }
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > context.limits.writeMaxBytes) {
            val limit = context.limits.writeMaxBytes
            return ToolResult.failure(
                ToolErrorCode.SIZE_LIMIT,
                "内容 " + bytes.size + " 字节超出写入上限 " + limit + " 字节",
                elapsedMs(start),
            )
        }
        val sandbox = context.sandbox
        return try {
            val existed = sandbox.exists(path)
            sandbox.writeText(path, content)
            val verb = if (existed) "已覆盖" else "已创建"
            ToolResult.success(
                verb + ": " + path + "（" + content.length + " 字符 / " + bytes.size + " 字节）",
                durationMs = elapsedMs(start),
            )
        } catch (e: SandboxPathException) {
            ToolResult.failure(ToolErrorCode.PATH_ESCAPE, "路径越界: " + e.message, elapsedMs(start))
        } catch (e: SandboxIoException) {
            ToolResult.failure(ToolErrorCode.IO, "写入失败: " + (e.message ?: "IO 错误"), elapsedMs(start))
        } catch (e: Exception) {
            ToolResult.failure(ToolErrorCode.IO, "写入异常: " + (e.message ?: e.javaClass.simpleName), elapsedMs(start))
        }
    }

    companion object {
        const val NAME = "write"
    }
}
