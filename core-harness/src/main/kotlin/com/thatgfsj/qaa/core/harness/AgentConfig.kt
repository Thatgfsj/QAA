package com.thatgfsj.qaa.core.harness

/**
 * Agent 配置契约（docs/ARCHITECTURE.md 第 3 节）。
 *
 * 手机端默认关闭 YOLO；字段扩展（SteerQueue / ContextCompactor 等运行参数）
 * 随后续议题逐步补充，修改需通过 Issue → PR。
 */
data class AgentConfig(
    val yoloMode: Boolean = false,
    val maxToolSteps: Int = 12,
    val compactThresholdRatio: Float = 0.8f,
)
