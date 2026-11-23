package com.thatgfsj.qaa.assembly

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SAF 文档 ID 映射单测：卷内相对路径、卷根与越界拒绝。 */
class SafTreePathTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun 主存储文档ID映射到卷内目录() {
        val primary = folder.newFolder("primary")
        val resolved = SafTreePath.resolve("primary:Documents/QAA") { primary }
        assertEquals(File(primary, "Documents/QAA").canonicalFile, resolved)
    }

    @Test
    fun 卷根与空路径段合法() {
        val primary = folder.newFolder("primary")
        assertEquals(primary.canonicalFile, SafTreePath.resolve("primary:") { primary })
        assertEquals(
            File(primary, "Documents").canonicalFile,
            SafTreePath.resolve("primary:/Documents//") { primary },
        )
    }

    @Test
    fun 非法卷与越界路径被拒绝() {
        val primary = folder.newFolder("primary")
        val volumes: (String) -> File? = { volume -> if (volume == "primary") primary else null }
        assertRejected("unknown:Documents", volumes)
        assertRejected("primary:../escape") { primary }
        assertRejected("primary:Documents/../escape") { primary }
        assertRejected("primary:.") { primary }
        assertRejected("primary") { primary }
    }

    private fun assertRejected(docId: String, volume: (String) -> File?) {
        val error = runCatching { SafTreePath.resolve(docId, volume) }.exceptionOrNull()
        assertTrue("应拒绝文档 ID：$docId，实际：$error", error is IllegalArgumentException)
    }
}
