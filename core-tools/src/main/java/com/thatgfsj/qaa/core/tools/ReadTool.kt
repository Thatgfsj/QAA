package com.thatgfsj.qaa.core.tools

/**
 * read 工具（docs/design/harness-core-design.md §3.3）。
 *
 * 只读，非变更类：requiresConfirmation = false。
 * 文本文件：返回内容，超 limits.read 截断并附截断标记与总长；
 * 目录：返回按名称稳定排序的目录清单（d 名/ 与 f 名（N 字节）行）。
 */
class ReadTool : ToolExecutor {

    override val name: String = NAME
    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argsJson: String, context: ToolContext): ToolResult {
        val start = System.nanoTime()
        val path = try {
            ToolArgs.requireString(ToolArgs.parseObject(argsJson), "path")
        } catch (e: ToolArgsException) {
            return ToolResult.failure(ToolErrorCode.ARGS_INVALID, e.message ?: "参数无效", elapsedMs(start))
        }
        val sandbox = context.sandbox
        return try {
            if (sandbox.isDirectory(path)) {
                val entries = sandbox.listDirectory(path)
                ToolResult.success(formatDirectory(path, entries), durationMs = elapsedMs(start))
            } else {
                val max = context.limits.readMaxChars
                val text = sandbox.readText(path, max)
                val total = if (text.length < max) text.length.toLong() else sandbox.textLength(path)
                val output = if (total > max) {
                    text + "\n\n…[已截断：文件共 " + total + " 字符，仅显示前 " + max + " 字符]"
                } else {
                    text
                }
                ToolResult.success(output, durationMs = elapsedMs(start))
            }
        } catch (e: SandboxPathException) {
            ToolResult.failure(ToolErrorCode.PATH_ESCAPE, "路径越界: " + e.message, elapsedMs(start))
        } catch (e: SandboxFileNotFoundException) {
            ToolResult.failure(ToolErrorCode.NOT_FOUND, "文件不存在: " + path, elapsedMs(start))
        } catch (e: SandboxBinaryFileException) {
            ToolResult.failure(ToolErrorCode.BINARY_FILE, "二进制文件，read 仅支持文本: " + path, elapsedMs(start))
        } catch (e: SandboxIoException) {
            ToolResult.failure(ToolErrorCode.IO, "读取失败: " + (e.message ?: "IO 错误"), elapsedMs(start))
        } catch (e: Exception) {
            ToolResult.failure(ToolErrorCode.IO, "读取异常: " + (e.message ?: e.javaClass.simpleName), elapsedMs(start))
        }
    }

    /** 目录清单的稳定格式：首行统计，随后每项一行（按名称排序）。 */
    internal fun formatDirectory(path: String, entries: List<SandboxEntry>): String {
        val sb = StringBuilder()
        sb.append("目录 ").append(path).append("（").append(entries.size).append(" 项）")
        for (entry in entries) {
            sb.append('\n')
            if (entry.isDirectory) {
                sb.append("d ").append(entry.name).append('/')
            } else {
                sb.append("f ").append(entry.name).append("（").append(entry.sizeBytes).append(" 字节）")
            }
        }
        return sb.toString()
    }

    companion object {
        const val NAME = "read"
    }
}
