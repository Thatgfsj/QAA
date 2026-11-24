package com.thatgfsj.qaa.feature.terminal

/**
 * Shell 命令解析器（Issue #10）：纯函数、纯 Kotlin，与 Compose / Android 完全解耦，可 JVM 单测。
 *
 * 双状态机：SHELL 顶层态解析命令；SESSION 会话态解析控制命令（/ 开头），
 * 其余文字一律视为用户 prompt。解析器只产出 [ShellParseResult]，状态流转与视图更新
 * 由 [TerminalShellController] 负责。
 */
object ShellCommandParser {

    private val WHITESPACE = Regex("\\s+")

    /** 解析一行输入；空行返回无输出、无副作用的结果，阶段保持不变。 */
    fun parse(input: String, context: ShellContext = ShellContext()): ShellParseResult {
        val line = input.trim()
        if (line.isEmpty()) return ShellParseResult(context.stage)
        val tokens = line.split(WHITESPACE)
        return when (context.stage) {
            ShellStage.SHELL -> parseShellCommand(tokens, context)
            ShellStage.SESSION -> parseSessionCommand(tokens, line, context)
        }
    }

    // ---- SHELL 顶层态 ----

    private fun parseShellCommand(tokens: List<String>, context: ShellContext): ShellParseResult {
        return when (tokens.first().lowercase()) {
            "help", "/help" -> reply(context, shellHelpText())
            "model" -> parseModel(tokens, context)
            "chat" -> enterSession(tokens, TerminalMode.CHAT, context)
            "agent" -> enterSession(tokens, TerminalMode.AGENT, context)
            "start" -> parseStartWindow(tokens, context)
            "backend" -> parseBackend(tokens, context)
            "storage" -> parseStorage(tokens, context)
            "clear" -> parseClear(tokens, context)
            "/logs" -> logs(tokens, context)
            "/status" -> statusResult(context)
            "/exit" -> error(context, "当前已在 Shell 顶层态，无需 /exit。输入 help 查看可用命令。")
            else -> unknownShellCommand(tokens.first(), context)
        }
    }

    private fun parseModel(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size == 1) return reply(context, localModelListText(context.models, context.modelInfo))
        return when (tokens[1].lowercase()) {
            "pull" -> when {
                tokens.size == 2 -> usage(context, "model pull <url>", "需要提供模型下载地址（http / https）。")
                tokens.size > 3 -> usage(context, "model pull <url>", "model pull 只接受一个下载地址参数。")
                !isHttpUrl(tokens[2]) -> error(
                    context,
                    "下载地址无效：${tokens[2]}。请提供以 http:// 或 https:// 开头的地址。",
                )
                else -> ShellParseResult(
                    stage = context.stage,
                    output = listOf(
                        "开始下载模型：${tokens[2]}",
                        "下载支持断点续传，进度实时回显；完成后模型会登记进本地清单。",
                    ),
                    effects = listOf(ShellEffect.PullModel(tokens[2])),
                )
            }
            "import" -> if (tokens.size > 2) {
                usage(context, "model import", "model import 不接受参数，导入文件由系统文件选择器（SAF）提供。")
            } else {
                ShellParseResult(
                    stage = context.stage,
                    output = listOf("正在打开系统文件选择器，请选择要导入的 .gguf 模型文件…"),
                    effects = listOf(ShellEffect.ImportModel),
                )
            }
            else -> error(
                context,
                "未知 model 子命令：${tokens[1]}。可用：model、model pull <url>、model import。",
            )
        }
    }

    private fun enterSession(
        tokens: List<String>,
        mode: TerminalMode,
        context: ShellContext,
    ): ShellParseResult {
        val command = mode.displayTag.lowercase()
        val modelToken = tokens.getOrNull(1)
        if (tokens.size > 2) return usage(context, "$command [模型名|序号]", "$command 只接受一个模型参数。")
        if (modelToken == null) {
            if (mode == TerminalMode.CHAT) {
                return usage(
                    context,
                    "chat <模型名|序号>",
                    "CHAT 会话需要指定本地模型，例如 chat 0；输入 model 查看本地模型清单。",
                )
            }
            return enterSessionResult(mode, model = null)
        }
        val model = resolveModel(modelToken, context)
            ?: return error(
                context,
                "未找到本地模型：$modelToken。输入 model 查看本地模型清单（名称或序号，内置模型为 0 号）。",
            )
        return enterSessionResult(mode, model)
    }

    /**
     * 进入会话的统一产物：先清屏再应用模式 / 模型副作用。
     *
     * 不追加任何系统回显——顶栏 [CHAT] / [AGENT] 承担「已进入会话」的告知职责，
     * 首条用户输入才出现「你 &lt;」行（#81）。
     */
    private fun enterSessionResult(
        mode: TerminalMode,
        model: LocalModelEntry?,
    ): ShellParseResult {
        val effects = buildList {
            add(ShellEffect.ClearScreen)
            add(ShellEffect.SetMode(mode))
            if (model != null) add(ShellEffect.SelectModel(model))
        }
        return ShellParseResult(stage = ShellStage.SESSION, effects = effects)
    }

    private fun parseStartWindow(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size != 2 || !tokens[1].equals("window", ignoreCase = true)) {
            return usage(context, "start window", "start 仅支持 start window，且不接受多余参数。")
        }
        return ShellParseResult(
            stage = context.stage,
            output = listOf("正在切回正常窗口，App 将立即重启…"),
            effects = listOf(ShellEffect.StartNormalWindow),
        )
    }

    private fun parseBackend(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size == 1) {
            return reply(
                context,
                "请求后端：${context.requestedBackend.displayName}",
                "实际命中：${context.activeBackend.displayTag}",
                "用法：backend [auto|npu|hybrid|cpu]；无参数查看，带参数锁定请求后端。",
            )
        }
        if (tokens.size > 2) return usage(context, "backend [auto|npu|hybrid|cpu]", "backend 只接受一个参数。")
        val request = BackendRequest.fromCommand(tokens[1])
            ?: return error(context, "无效的后端参数：${tokens[1]}。可选值：auto、npu、hybrid、cpu。")
        val lines = if (request == BackendRequest.AUTO) {
            listOf("已恢复自动降级链（请求后端 auto）；实际命中由推理结果回显。")
        } else {
            listOf(
                "已锁定请求后端：${request.displayName}；引擎已加载时立即按新后端重载。",
            )
        }
        return ShellParseResult(
            stage = context.stage,
            output = lines,
            effects = listOf(ShellEffect.SetBackend(request)),
        )
    }

    private fun parseStorage(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size == 2 && tokens[1].equals("grant", ignoreCase = true)) {
            return ShellParseResult(
                stage = context.stage,
                output = listOf("正在打开系统目录选择器，请选择要授权给沙盒的外部目录…"),
                effects = listOf(ShellEffect.GrantStorage),
            )
        }
        return usage(context, "storage grant", "storage 仅支持 storage grant 一个子命令。")
    }

    private fun parseClear(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size > 1) return usage(context, "clear", "clear 不接受参数。")
        return ShellParseResult(
            stage = context.stage,
            output = listOf("已清空当前会话区。"),
            effects = listOf(ShellEffect.ClearScreen),
        )
    }

    private fun unknownShellCommand(head: String, context: ShellContext): ShellParseResult =
        if (head.startsWith("/")) {
            error(context, "未知命令：$head。会话命令需先通过 chat / agent 进入会话态；输入 help 查看可用命令。")
        } else {
            error(context, "未知命令：$head。输入 help 查看可用命令。")
        }

    // ---- SESSION 会话态 ----

    private fun parseSessionCommand(
        tokens: List<String>,
        line: String,
        context: ShellContext,
    ): ShellParseResult {
        val head = tokens.first()
        if (!head.startsWith("/")) {
            return ShellParseResult(context.stage, effects = listOf(ShellEffect.ForwardPrompt(line)))
        }
        return when (head.lowercase()) {
            "/help" -> reply(context, shellHelpText())
            "/status" -> statusResult(context)
            "/exit" -> exitSession(tokens, context)
            "/new" -> singleEffect(tokens, context, "/new", ShellEffect.NewSession)
            "/tree" -> singleEffect(tokens, context, "/tree", ShellEffect.QueryTree)
            "/fork" -> fork(tokens, context)
            "/compact" -> singleEffect(tokens, context, "/compact", ShellEffect.CompactContext)
            "/steer" -> steer(tokens, line, context)
            "/skills" -> singleEffect(tokens, context, "/skills", ShellEffect.QuerySkills)
            "/export" -> export(tokens, context)
            "/logs" -> logs(tokens, context)
            else -> error(
                context,
                "未知会话命令：$head。可用命令：/exit、/new、/tree、/fork、/compact、/steer、/skills、/export、/logs；" +
                    "输入 /help 查看完整说明。",
            )
        }
    }

    /** /status：先回显快照态，再向 Agent 端口追加运行态明细（模型清单、会话树、队列）。 */
    private fun statusResult(context: ShellContext): ShellParseResult = ShellParseResult(
        stage = context.stage,
        output = listOf(shellStatusText(context)),
        effects = listOf(ShellEffect.QueryStatusExtra),
    )

    private fun exitSession(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size > 1) return usage(context, "/exit", "/exit 不接受参数。")
        return ShellParseResult(
            stage = ShellStage.SHELL,
            output = listOf("已退出会话，返回 Shell 顶层态。输入 help 查看命令。"),
        )
    }

    private fun fork(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size > 2) return usage(context, "/fork [节点ID]", "/fork 最多接受一个节点 ID。")
        return ShellParseResult(
            stage = context.stage,
            effects = listOf(ShellEffect.ForkNode(tokens.getOrNull(1))),
        )
    }

    /** /export [路径]：路径可省略（默认沙盒 exports/session-<会话id>.jsonl）。 */
    private fun export(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size > 2) return usage(context, "/export [路径]", "/export 最多接受一个导出路径。")
        return ShellParseResult(
            stage = context.stage,
            effects = listOf(ShellEffect.ExportTrace(tokens.getOrNull(1))),
        )
    }

    /**
     * /logs [行数]：行数缺省交给运行链（默认 200 行），显式给出时校验 1..[APP_LOG_MAX_LINES]。
     * 两个阶段都可用：初始化失败时也能在 Shell 顶层态导出日志取证。
     */
    private fun logs(tokens: List<String>, context: ShellContext): ShellParseResult {
        if (tokens.size > 2) return usage(context, "/logs [行数]", "/logs 最多接受一个行数参数。")
        val token = tokens.getOrNull(1)
            ?: return ShellParseResult(context.stage, effects = listOf(ShellEffect.ExportLogs(null)))
        val lines = token.toIntOrNull()
        if (lines == null || lines < 1 || lines > APP_LOG_MAX_LINES) {
            return error(
                context,
                "行数无效：$token。用法：/logs [行数]，范围 1-$APP_LOG_MAX_LINES。",
            )
        }
        return ShellParseResult(context.stage, effects = listOf(ShellEffect.ExportLogs(lines)))
    }

    private fun steer(tokens: List<String>, line: String, context: ShellContext): ShellParseResult {
        if (tokens.size < 2) return usage(context, "/steer <指令>", "/steer 需要附带干预指令。")
        val instruction = line.substringAfter(tokens.first()).trim()
        return ShellParseResult(
            stage = context.stage,
            output = listOf("已提交干预指令：$instruction（运行中在当前工具步结束后生效）。"),
            effects = listOf(ShellEffect.SteerInstruction(instruction)),
        )
    }

    /** 无参数查询类命令：参数多于命令本身即报用法，否则只产出执行体副作用。 */
    private fun singleEffect(
        tokens: List<String>,
        context: ShellContext,
        command: String,
        effect: ShellEffect,
    ): ShellParseResult {
        if (tokens.size > 1) return usage(context, command, "$command 不接受参数。")
        return ShellParseResult(stage = context.stage, effects = listOf(effect))
    }

    // ---- 公共辅助 ----

    private fun resolveModel(token: String, context: ShellContext): LocalModelEntry? {
        val index = token.toIntOrNull()
        return if (index != null) {
            context.models.firstOrNull { it.index == index }
        } else {
            context.models.firstOrNull { it.name.equals(token, ignoreCase = true) }
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        (value.startsWith("http://") || value.startsWith("https://")) && value.length > "https://".length

    private fun reply(context: ShellContext, vararg lines: String): ShellParseResult =
        ShellParseResult(stage = context.stage, output = lines.toList())

    private fun error(context: ShellContext, message: String): ShellParseResult =
        ShellParseResult(stage = context.stage, output = listOf(message))

    private fun usage(context: ShellContext, usage: String, hint: String): ShellParseResult =
        ShellParseResult(stage = context.stage, output = listOf("参数不正确。用法：$usage", hint))
}
