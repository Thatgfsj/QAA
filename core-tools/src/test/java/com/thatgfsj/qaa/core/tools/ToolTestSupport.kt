package com.thatgfsj.qaa.core.tools

import org.junit.rules.TemporaryFolder
import java.io.File

/** 测试用 ToolContext 装配（生产装配由宿主负责）。 */
class TestToolContext(
    override val sessionId: String = "test-session",
    override val stepIndex: Int = 1,
    override val sandbox: SandboxFs,
    override val limits: ToolLimits = ToolLimits(),
) : ToolContext

/** 在 TemporaryFolder 下建一个单授权根（id = "main"）的沙盒。 */
fun tempSandbox(tmp: TemporaryFolder, rootName: String = "root"): Pair<LocalSandboxFs, File> {
    val root = tmp.newFolder(rootName)
    return LocalSandboxFs(listOf(SandboxRoot.File("main", root.toPath()))) to root
}
