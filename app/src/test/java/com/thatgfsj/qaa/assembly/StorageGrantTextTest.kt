package com.thatgfsj.qaa.assembly

import com.thatgfsj.qaa.core.tools.SafDocumentGateway
import com.thatgfsj.qaa.core.tools.SafDocumentInfo
import com.thatgfsj.qaa.core.tools.SandboxRoot
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** storage grant 回显：只展示树 Uri 与 displayName，不泄露不可用的真实路径。 */
class StorageGrantTextTest {

    @Test
    fun 回显展示树Uri与目录名且不含真实路径() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FQAA"
        val text = StorageGrantText.mountMessage(
            displayName = "QAA 文档",
            treeUri = treeUri,
            readOnly = false,
            replaced = null,
        )
        assertTrue(text.contains("QAA 文档"))
        assertTrue(text.contains(treeUri))
        assertTrue(text.contains("\"ext\""))
        assertFalse(text.contains("/storage/"))
    }

    @Test
    fun 只读授权与替换旧根均有明确提示() {
        val text = StorageGrantText.mountMessage(
            displayName = "新目录",
            treeUri = "content://provider/tree/new",
            readOnly = true,
            replaced = SandboxRoot.Saf("ext", "content://provider/tree/old", "旧目录", UnusedGateway),
        )
        assertTrue(text.contains("只读"))
        assertTrue(text.contains("旧目录"))
        assertTrue(text.contains("content://provider/tree/old"))
        assertFalse(text.contains("/storage/"))
    }

    @Test
    fun 旧版本真实路径挂载也不泄露路径() {
        val text = StorageGrantText.mountMessage(
            displayName = "新目录",
            treeUri = "content://provider/tree/new",
            readOnly = false,
            replaced = SandboxRoot.File("ext", java.nio.file.Paths.get("/storage/emulated/0/QAA")),
        )
        assertFalse(text.contains("/storage/"))
        assertTrue(text.contains("已解除"))
    }

    private object UnusedGateway : SafDocumentGateway {
        override fun queryDocument(treeUri: String, relativePath: String): SafDocumentInfo? = null

        override fun openRead(treeUri: String, relativePath: String): InputStream = error("未使用")

        override fun openWrite(treeUri: String, relativePath: String): OutputStream = error("未使用")

        override fun listChildren(treeUri: String, relativePath: String): List<SafDocumentInfo> = emptyList()
    }
}
