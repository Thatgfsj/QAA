package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.harness.ToolCall
import com.thatgfsj.qaa.core.harness.ToolOutcomeStatus
import com.thatgfsj.qaa.core.tools.DrawImageTool
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SafDocumentGateway
import com.thatgfsj.qaa.core.tools.SafDocumentInfo
import com.thatgfsj.qaa.core.tools.SafGatewayException
import com.thatgfsj.qaa.core.tools.SafNotFoundException
import com.thatgfsj.qaa.core.tools.SandboxRoot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 工具端口经 SAF 根执行四原语：工具层零改动即可读写编辑外部授权目录。 */
class ToolRouterGatewaySafTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun 四原语工具经SAF根读写编辑() = runTest {
        val documents = InMemorySafGateway()
        val sandbox = LocalSandboxFs(
            listOf(
                SandboxRoot.File("files", folder.newFolder("files").toPath()),
                SandboxRoot.Saf("ext", "content://test/tree/ext", "外部目录", documents),
            )
        )
        val gateway = ToolRouterGateway(
            sandbox = sandbox,
            sessionId = "test-session",
            executors = ToolRouterGateway.defaultExecutors(DrawImageTool()),
        )

        val write = gateway.execute(
            ToolCall("write", """{"path":"ext/docs/note.txt","content":"第一版"}"""),
            0,
        )
        assertEquals(ToolOutcomeStatus.SUCCESS, write.status)

        val read = gateway.execute(ToolCall("read", """{"path":"ext/docs/note.txt"}"""), 1)
        assertEquals(ToolOutcomeStatus.SUCCESS, read.status)
        assertTrue(read.output.contains("第一版"))

        val edit = gateway.execute(
            ToolCall("edit", """{"path":"ext/docs/note.txt","oldText":"第一版","newText":"第二版"}"""),
            2,
        )
        assertEquals(ToolOutcomeStatus.SUCCESS, edit.status)
        assertEquals("第二版", documents.textOf("docs/note.txt"))
    }

    /** 最小内存网关：只覆盖本测试用到的文件语义。 */
    private class InMemorySafGateway : SafDocumentGateway {

        private val files = LinkedHashMap<String, String>()

        fun textOf(path: String): String? = files[path]

        override fun queryDocument(treeUri: String, relativePath: String): SafDocumentInfo? {
            val content = files[relativePath] ?: return null
            return SafDocumentInfo(
                name = relativePath.substringAfterLast('/'),
                isDirectory = false,
                sizeBytes = content.toByteArray().size.toLong(),
            )
        }

        override fun openRead(treeUri: String, relativePath: String): InputStream {
            val content = files[relativePath] ?: throw SafNotFoundException("不存在：$relativePath")
            return ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        }

        override fun openWrite(treeUri: String, relativePath: String): OutputStream {
            if (relativePath.isEmpty()) throw SafGatewayException("不能写入授权树根目录")
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[relativePath] = String(toByteArray(), Charsets.UTF_8)
                    super.close()
                }
            }
        }

        override fun listChildren(treeUri: String, relativePath: String): List<SafDocumentInfo> = emptyList()
    }
}
