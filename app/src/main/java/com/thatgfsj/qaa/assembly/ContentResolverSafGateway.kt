package com.thatgfsj.qaa.assembly

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.thatgfsj.qaa.core.tools.SafDocumentGateway
import com.thatgfsj.qaa.core.tools.SafDocumentInfo
import com.thatgfsj.qaa.core.tools.SafGatewayException
import com.thatgfsj.qaa.core.tools.SafNotFoundException
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [SafDocumentGateway] 的系统实现：DocumentsContract 解析 DocumentId，ContentResolver 读写。
 *
 * 相对路径从授权树根开始逐段解析，用 buildChildDocumentsUriUsingTree 查询并匹配显示名，
 * 不依赖提供者的 DocumentId 拼接规则，也不构造 /storage 真实路径。
 * 所有提供者异常在边界处收敛为 [SafNotFoundException] / [SafGatewayException]。
 */
class ContentResolverSafGateway(private val resolver: ContentResolver) : SafDocumentGateway {

    private data class Child(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
    )

    override fun queryDocument(treeUri: String, relativePath: String): SafDocumentInfo? {
        val tree = parseTree(treeUri)
        val documentId = resolveDocumentIdOrNull(tree, relativePath) ?: return null
        return queryInfo(tree, documentId)
    }

    override fun openRead(treeUri: String, relativePath: String): InputStream {
        val tree = parseTree(treeUri)
        val documentId = requireDocumentId(tree, relativePath)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return try {
            resolver.openInputStream(uri)
                ?: throw SafGatewayException("无法打开输入流：$relativePath")
        } catch (e: SafGatewayException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw SafNotFoundException("文档不存在：$relativePath", e)
        } catch (e: SecurityException) {
            throw SafGatewayException("读取失败：$relativePath（授权已失效）", e)
        } catch (e: Exception) {
            throw SafGatewayException("读取失败：$relativePath（" + describe(e) + "）", e)
        }
    }

    override fun openWrite(treeUri: String, relativePath: String): OutputStream {
        val tree = parseTree(treeUri)
        val parts = pathSegments(relativePath)
        if (parts.isEmpty()) throw SafGatewayException("不能写入授权树根目录")
        val directoryId = ensureDirectories(tree, parts.dropLast(1), relativePath)
        val name = parts.last()
        val existing = findChild(tree, directoryId, name)
        val documentId = if (existing == null) {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, directoryId)
            val created = try {
                DocumentsContract.createDocument(resolver, parentUri, mimeTypeFor(name), name)
            } catch (e: SecurityException) {
                throw SafGatewayException("创建文档失败：$relativePath（授权已失效）", e)
            } catch (e: Exception) {
                throw SafGatewayException("创建文档失败：$relativePath（" + describe(e) + "）", e)
            } ?: throw SafGatewayException("创建文档失败：$relativePath")
            DocumentsContract.getDocumentId(created)
        } else {
            if (existing.isDirectory) throw SafGatewayException("目标是目录：$relativePath")
            existing.documentId
        }
        return openTruncating(DocumentsContract.buildDocumentUriUsingTree(tree, documentId), relativePath)
    }

    override fun listChildren(treeUri: String, relativePath: String): List<SafDocumentInfo> {
        val tree = parseTree(treeUri)
        val documentId = requireDocumentId(tree, relativePath)
        val info = queryInfo(tree, documentId)
            ?: throw SafNotFoundException("目录不存在：$relativePath")
        if (!info.isDirectory) throw SafGatewayException("目标不是目录：$relativePath")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val result = ArrayList<SafDocumentInfo>()
        query(childrenUri, "列目录失败：$relativePath") { cursor ->
            while (cursor.moveToNext()) {
                val child = readChild(cursor)
                result.add(SafDocumentInfo(child.name, child.isDirectory, child.sizeBytes))
            }
        }
        return result
    }

    // ---- 树内路径解析 ----

    private fun resolveDocumentIdOrNull(tree: Uri, relativePath: String): String? {
        var current = DocumentsContract.getTreeDocumentId(tree)
        for (segment in pathSegments(relativePath)) {
            val child = findChild(tree, current, segment) ?: return null
            current = child.documentId
        }
        return current
    }

    private fun requireDocumentId(tree: Uri, relativePath: String): String =
        resolveDocumentIdOrNull(tree, relativePath)
            ?: throw SafNotFoundException("文档不存在：$relativePath")

    private fun findChild(tree: Uri, parentDocumentId: String, name: String): Child? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        return query(childrenUri, "列目录失败：$name") { cursor ->
            while (cursor.moveToNext()) {
                val child = readChild(cursor)
                if (child.name == name) return@query child
            }
            null
        }
    }

    /** 创建缺失的父目录（与 File 根 write 的「父目录自动创建」保持一致）。 */
    private fun ensureDirectories(tree: Uri, segments: List<String>, display: String): String {
        var current = DocumentsContract.getTreeDocumentId(tree)
        for (segment in segments) {
            val child = findChild(tree, current, segment)
            current = when {
                child == null -> {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, current)
                    val created = try {
                        DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, segment)
                    } catch (e: SecurityException) {
                        throw SafGatewayException("创建目录失败：$display（授权已失效）", e)
                    } catch (e: Exception) {
                        throw SafGatewayException("创建目录失败：$display（" + describe(e) + "）", e)
                    } ?: throw SafGatewayException("创建目录失败：$display")
                    DocumentsContract.getDocumentId(created)
                }
                child.isDirectory -> child.documentId
                else -> throw SafGatewayException("父路径不是目录：$display")
            }
        }
        return current
    }

    private fun pathSegments(relativePath: String): List<String> {
        if (relativePath.isEmpty()) return emptyList()
        if (relativePath.startsWith("/")) {
            throw SafGatewayException("相对路径不能以 / 开头：$relativePath")
        }
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) {
            throw SafGatewayException("相对路径含非法段：$relativePath")
        }
        return parts
    }

    // ---- DocumentsContract 查询 ----

    private fun queryInfo(tree: Uri, documentId: String): SafDocumentInfo? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return query(uri, "查询文档失败：$documentId") { cursor ->
            if (!cursor.moveToFirst()) return@query null
            val child = readChild(cursor)
            SafDocumentInfo(child.name, child.isDirectory, child.sizeBytes)
        }
    }

    private fun readChild(cursor: Cursor): Child {
        val documentId = cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID))
        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
            ?.takeIf { it.isNotBlank() }
            ?: documentId.substringAfterLast('/')
        val isDirectory = cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)) ==
            Document.MIME_TYPE_DIR
        val sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE)
        val size = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) -1L else cursor.getLong(sizeIndex)
        return Child(documentId, displayName, isDirectory, size)
    }

    private fun <T> query(uri: Uri, error: String, block: (Cursor) -> T): T {
        val result = try {
            resolver.query(uri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw SafGatewayException(error + "（授权已失效）", e)
        } catch (e: Exception) {
            throw SafGatewayException(error + "（" + describe(e) + "）", e)
        }
        val cursor = result ?: throw SafGatewayException(error + "（提供者未返回游标）")
        return cursor.use(block)
    }

    // ---- 流与 MIME ----

    private fun openTruncating(uri: Uri, display: String): OutputStream {
        val truncated = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        }
        if (truncated != null) return truncated
        return try {
            resolver.openOutputStream(uri) ?: throw SafGatewayException("无法打开输出流：$display")
        } catch (e: SafGatewayException) {
            throw e
        } catch (e: SecurityException) {
            throw SafGatewayException("写入失败：$display（授权已失效）", e)
        } catch (e: Exception) {
            throw SafGatewayException("写入失败：$display（" + describe(e) + "）", e)
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun parseTree(treeUri: String): Uri {
        if (treeUri.isBlank()) throw SafGatewayException("授权树 Uri 为空")
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri)) {
            throw SafGatewayException("不是授权目录树 Uri：$treeUri")
        }
        return uri
    }

    private fun describe(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
    }
}
