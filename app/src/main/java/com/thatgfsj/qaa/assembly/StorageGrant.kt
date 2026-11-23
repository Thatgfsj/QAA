package com.thatgfsj.qaa.assembly

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import com.thatgfsj.qaa.core.tools.LocalSandboxFs
import com.thatgfsj.qaa.core.tools.SafGatewayException
import com.thatgfsj.qaa.core.tools.SandboxRoot

/**
 * 外部存储目录授权：把 SAF 树 Uri 持久化为系统授权，并作为 "ext" 根挂载进组合沙盒。
 *
 * 目录内容全程经 [ContentResolverSafGateway] 以 DocumentsContract 语义访问，
 * 不映射为 /storage 真实路径（targetSdk 35 分区存储下 App 无权直访该路径）。
 * 授权记录保存在系统 persistedUriPermissions，当前树 Uri 另存 SharedPreferences
 * 供进程重启后恢复；重复授权替换旧根并释放旧权限。
 */
class StorageGrant(context: Context, private val sandbox: LocalSandboxFs) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 授权所选目录树并挂载为 ext 根；返回中文回显。 */
    fun mount(uri: Uri): String {
        if (!DocumentsContract.isTreeUri(uri)) {
            return "存储授权失败：所选位置不是目录树。"
        }
        val permission = try {
            takePersistable(uri)
        } catch (error: SecurityException) {
            return "存储授权失败：无法持久化目录访问权限（" + (error.message ?: "系统拒绝") + "）。"
        }
        val gateway = ContentResolverSafGateway(appContext.contentResolver)
        val info = try {
            gateway.queryDocument(uri.toString(), "")
        } catch (error: SafGatewayException) {
            return "存储授权失败：无法读取所选目录（" + (error.message ?: "提供者拒绝访问") + "）。"
        } ?: return "存储授权失败：无法读取所选目录。"
        if (!info.isDirectory) {
            return "存储授权失败：所选位置不是可读目录。"
        }
        val replaced = try {
            sandbox.addRoot(SandboxRoot.Saf(ROOT_ID, uri.toString(), info.name, gateway))
        } catch (error: IllegalArgumentException) {
            return "存储授权失败：" + error.message
        }
        val previous = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        if (previous != null && previous != uri) releaseQuietly(previous)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        return StorageGrantText.mountMessage(
            displayName = info.name,
            treeUri = uri.toString(),
            readOnly = !permission.isWritePermission,
            replaced = replaced,
        )
    }

    /** 按上次保存的树 Uri 恢复挂载；系统授权缺失时清理记录。 */
    fun restore() {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = Uri.parse(stored)
        val granted = appContext.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        if (!granted) {
            prefs.edit().remove(KEY_TREE_URI).apply()
            return
        }
        val gateway = ContentResolverSafGateway(appContext.contentResolver)
        val queried = try {
            gateway.queryDocument(uri.toString(), "")
        } catch (_: SafGatewayException) {
            // 提供者暂不可用：保留记录，下次启动再试。
            return
        }
        val info = queried ?: run {
            prefs.edit().remove(KEY_TREE_URI).apply()
            return
        }
        if (!info.isDirectory) {
            prefs.edit().remove(KEY_TREE_URI).apply()
            return
        }
        runCatching { sandbox.addRoot(SandboxRoot.Saf(ROOT_ID, uri.toString(), info.name, gateway)) }
    }

    private fun takePersistable(uri: Uri): UriPermission {
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
        return resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: throw SecurityException("系统未记录目录授权")
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

    companion object {

        /** 外部目录的授权根 id：工具路径首段。 */
        const val ROOT_ID = "ext"

        private const val PREFS_NAME = "storage_grant"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
