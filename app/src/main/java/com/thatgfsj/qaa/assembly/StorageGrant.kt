package com.thatgfsj.qaa.assembly

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SandboxRoot
import java.io.File

/**
 * 外部存储目录授权：把 SAF 树 Uri 持久化为系统授权，映射为真实目录后挂载成沙盒的 "ext" 根。
 *
 * 只有主存储（primary / home）与可枚举的二级卷能映射为真实目录，其他提供者明确报错，
 * 不做隐式降级。授权记录保存在系统 persistedUriPermissions，当前树 Uri 另存
 * SharedPreferences 供进程重启后恢复；重复授权替换旧根并释放旧权限。
 */
class StorageGrant(context: Context, private val sandbox: LocalSandboxFs) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 授权所选目录并挂载为 ext 根；返回中文回显。 */
    fun mount(uri: Uri): String {
        try {
            takePersistable(uri)
        } catch (error: SecurityException) {
            return "存储授权失败：无法持久化目录访问权限（${error.message ?: "系统拒绝"}）。"
        }
        val tree = DocumentFile.fromTreeUri(appContext, uri)
            ?: return "存储授权失败：无法解析所选目录。"
        if (!tree.isDirectory || !tree.canRead()) {
            return "存储授权失败：所选位置不是可读目录。"
        }
        val directory = try {
            resolveDirectory(uri)
        } catch (error: IllegalArgumentException) {
            return "存储授权失败：${error.message}"
        }
        val replaced = try {
            sandbox.addRoot(SandboxRoot(ROOT_ID, directory.toPath()))
        } catch (error: IllegalArgumentException) {
            return "存储授权失败：${error.message}"
        }
        val previousUri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        if (previousUri != null && previousUri != uri) releaseQuietly(previousUri)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        return mountMessage(tree.name, directory, replaced)
    }

    /** 按上次保存的树 Uri 恢复挂载；权限或目录失效时清理记录。 */
    fun restore() {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = Uri.parse(stored)
        val granted = appContext.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        if (!granted) {
            prefs.edit().remove(KEY_TREE_URI).apply()
            return
        }
        val directory = runCatching { resolveDirectory(uri) }.getOrNull()
        if (directory == null) {
            prefs.edit().remove(KEY_TREE_URI).apply()
            return
        }
        runCatching { sandbox.addRoot(SandboxRoot(ROOT_ID, directory.toPath())) }
    }

    private fun takePersistable(uri: Uri) {
        val resolver = appContext.contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 部分提供者只授予读权限，退化为只读持久化。
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releaseQuietly(uri: Uri) {
        val permission = appContext.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags == 0) return
        runCatching { appContext.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun resolveDirectory(uri: Uri): File {
        if (!DocumentsContract.isTreeUri(uri)) {
            throw IllegalArgumentException("所选位置不是目录树。")
        }
        val authority = uri.authority
        if (authority != EXTERNAL_STORAGE_AUTHORITY) {
            throw IllegalArgumentException("仅支持系统外部存储提供者，当前为 $authority。")
        }
        val directory = SafTreePath.resolve(DocumentsContract.getTreeDocumentId(uri), ::volumeDirectory)
        if (!directory.isDirectory) {
            throw IllegalArgumentException("授权目录不存在：${directory.absolutePath}")
        }
        return directory
    }

    @Suppress("DEPRECATION")
    private fun volumeDirectory(volume: String): File? = when (volume.lowercase()) {
        VOLUME_PRIMARY -> Environment.getExternalStorageDirectory()
        VOLUME_HOME -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        else -> secondaryVolumeDirectory(volume)
    }

    private fun secondaryVolumeDirectory(uuid: String): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = appContext.getSystemService(StorageManager::class.java) ?: return null
        return manager.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.directory
    }

    private fun mountMessage(displayName: String?, directory: File, replaced: SandboxRoot?): String = buildString {
        appendLine("外部存储授权已生效：根 id 为 \"$ROOT_ID\"，目录 ${displayName ?: directory.name}")
        appendLine("授权目录：${directory.absolutePath}")
        if (replaced != null && replaced.directory != directory.toPath()) {
            appendLine("已替换原有授权根（旧目录：${replaced.directory}），旧目录不再挂载。")
        }
        appendLine(
            "可用范围：read / write / edit 通过 \"$ROOT_ID/<相对路径>\" 访问该目录；" +
                "不带首段的路径仍指向 App 私有目录。",
        )
        append("授权持久化：系统 SAF 授权，重启后自动恢复。")
    }

    companion object {

        /** 外部目录的授权根 id：工具路径首段。 */
        const val ROOT_ID = "ext"

        private const val PREFS_NAME = "storage_grant"
        private const val KEY_TREE_URI = "tree_uri"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val VOLUME_PRIMARY = "primary"
        private const val VOLUME_HOME = "home"
    }
}
