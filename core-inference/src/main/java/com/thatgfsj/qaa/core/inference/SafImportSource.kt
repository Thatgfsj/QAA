package com.thatgfsj.qaa.core.inference

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * SAF 文档读取薄壳：把 ContentResolver / Uri 调用收敛在 [ContentResolverDocument]，
 * 导入逻辑与 JVM 单测只依赖本接口。
 */
interface SafDocument {

    /** OpenableColumns.DISPLAY_NAME；提供者未返回时为 null。 */
    fun displayName(): String?

    /** OpenableColumns.SIZE；未知为 null，导入按实际复制字节校验。 */
    fun sizeBytes(): Long?

    /** 打开只读输入流；授权失效或来源不存在返回 null。 */
    fun openStream(): InputStream?
}

/**
 * 系统文件选择器（ACTION_OPEN_DOCUMENT）选中文档的 [ImportSource] 实现。
 *
 * 一个实例固定对应一个文档，[open] / [size] 的 name 参数只用于满足接口。
 */
class SafImportSource(private val document: SafDocument) : ImportSource {

    override fun open(name: String): InputStream? = document.openStream()

    override fun size(name: String): Long? = document.sizeBytes()

    /** 选择器给出的展示名（含扩展名），供导入方决定落盘文件名。 */
    val suggestedName: String? get() = document.displayName()
}

/** 唯一接触 Android 内容解析器的薄壳：查询展示名与体积、打开输入流。 */
class ContentResolverDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : SafDocument {

    override fun displayName(): String? = queryColumn(OpenableColumns.DISPLAY_NAME)?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    override fun sizeBytes(): Long? = queryColumn(OpenableColumns.SIZE)?.toString()?.toLongOrNull()

    override fun openStream(): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()

    private fun queryColumn(column: String): Any? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) {
                null
            } else {
                cursor.getString(index)
            }
        }
    }.getOrNull()
}
