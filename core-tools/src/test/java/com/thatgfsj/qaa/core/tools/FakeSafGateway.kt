package com.thatgfsj.qaa.core.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 内存版 SAF 网关：以「相对路径 → 虚拟文档」模拟授权树，供组合根单测使用。
 *
 * 语义与 Android 实现保持一致：路径缺失返回 null / SafNotFoundException；
 * openWrite 覆盖写并自动创建父目录；listChildren 只返回直接子项。
 */
class FakeSafGateway : SafDocumentGateway {

    private class Node(val name: String, val isDirectory: Boolean, var content: ByteArray)

    private val nodes = LinkedHashMap<String, Node>().apply {
        put("", Node("授权目录", isDirectory = true, content = ByteArray(0)))
    }

    /** 注入查询失败（模拟授权失效、提供者异常）。 */
    var failQuery: SafGatewayException? = null

    /** 注入读取 / 写入 / 列目录失败。 */
    var failOpenRead: SafGatewayException? = null
    var failOpenWrite: SafGatewayException? = null
    var failList: SafGatewayException? = null

    var openReadCalls: Int = 0
        private set

    var openWriteCalls: Int = 0
        private set

    fun seed(path: String, content: String) = seedBytes(path, content.toByteArray(Charsets.UTF_8))

    fun seedBytes(path: String, content: ByteArray) {
        val normalized = normalize(path)
        require(normalized.isNotEmpty()) { "不能覆盖根" }
        createParents(normalized)
        nodes[normalized] = Node(normalized.substringAfterLast('/'), isDirectory = false, content = content)
    }

    fun seedDirectory(path: String) {
        val normalized = normalize(path)
        if (normalized.isEmpty()) return
        createParents(normalized)
        if (nodes[normalized] == null) {
            nodes[normalized] = Node(normalized.substringAfterLast('/'), isDirectory = true, content = ByteArray(0))
        }
    }

    fun textOf(path: String): String? {
        val node = nodes[normalize(path)] ?: return null
        return if (node.isDirectory) null else String(node.content, Charsets.UTF_8)
    }

    override fun queryDocument(treeUri: String, relativePath: String): SafDocumentInfo? {
        failQuery?.let { throw it }
        val node = nodes[normalize(relativePath)] ?: return null
        return SafDocumentInfo(
            name = node.name,
            isDirectory = node.isDirectory,
            sizeBytes = if (node.isDirectory) -1L else node.content.size.toLong(),
        )
    }

    override fun openRead(treeUri: String, relativePath: String): InputStream {
        openReadCalls++
        failOpenRead?.let { throw it }
        val path = normalize(relativePath)
        val node = nodes[path] ?: throw SafNotFoundException("文档不存在：$path")
        if (node.isDirectory) throw SafGatewayException("目标是目录：$path")
        return ByteArrayInputStream(node.content)
    }

    override fun openWrite(treeUri: String, relativePath: String): OutputStream {
        openWriteCalls++
        failOpenWrite?.let { throw it }
        val path = normalize(relativePath)
        if (path.isEmpty()) throw SafGatewayException("不能写入授权树根目录")
        createParents(path)
        val existing = nodes[path]
        if (existing?.isDirectory == true) throw SafGatewayException("目标是目录：$path")
        val name = path.substringAfterLast('/')
        return object : ByteArrayOutputStream() {
            override fun close() {
                nodes[path] = Node(name, isDirectory = false, content = toByteArray())
                super.close()
            }
        }
    }

    override fun listChildren(treeUri: String, relativePath: String): List<SafDocumentInfo> {
        failList?.let { throw it }
        val path = normalize(relativePath)
        val node = nodes[path] ?: throw SafNotFoundException("目录不存在：$path")
        if (!node.isDirectory) throw SafGatewayException("目标不是目录：$path")
        val prefix = if (path.isEmpty()) "" else path + "/"
        return nodes.entries.mapNotNull { (key, child) ->
            if (key.isEmpty() || !key.startsWith(prefix)) return@mapNotNull null
            if (key.removePrefix(prefix).contains('/')) return@mapNotNull null
            SafDocumentInfo(
                name = child.name,
                isDirectory = child.isDirectory,
                sizeBytes = if (child.isDirectory) -1L else child.content.size.toLong(),
            )
        }
    }

    private fun normalize(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun createParents(path: String) {
        val parts = path.split('/').dropLast(1)
        var current = ""
        for (part in parts) {
            val next = if (current.isEmpty()) part else current + "/" + part
            val existing = nodes[next]
            when {
                existing == null -> nodes[next] = Node(part, isDirectory = true, content = ByteArray(0))
                !existing.isDirectory -> throw SafGatewayException("父路径不是目录：$path")
            }
            current = next
        }
    }
}
