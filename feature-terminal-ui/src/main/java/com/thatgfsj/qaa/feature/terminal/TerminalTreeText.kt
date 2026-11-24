package com.thatgfsj.qaa.feature.terminal

/**
 * /tree 终端输出兜底（#99）。
 *
 * 树的渲染本体在 :core-harness 的 TreeSessionStore.renderTree()，终端只做两件事：
 * - **空态**：树为空或渲染为空白时给出明确中文提示，而不是静默无输出（真机曾出现 /tree 什么都不显示）；
 * - **可操作**：非空时追加图例与 /fork 用法提示，说明行首节点 ID 与 ● / ○ 的含义。
 */
fun sessionTreeOutput(raw: String): String {
    val body = raw.trimEnd()
    if (body.isBlank()) return EMPTY_TREE_TEXT
    return body + "\n" + TREE_FORK_HINT
}

/** 空会话树的终端回执。 */
const val EMPTY_TREE_TEXT: String =
    "（会话树为空：还没有可显示的节点。先发送一条提示词，再用 /tree 查看；/fork <节点ID> 可从任意节点分叉。）"

/** 非空树末尾的图例与 /fork 用法提示。 */
const val TREE_FORK_HINT: String = "（● 活动分支 / ○ 其它分支；/fork <节点ID> 从指定节点分叉）"
