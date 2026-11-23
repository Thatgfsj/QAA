package com.thatgfsj.qaa.assembly

import java.io.File

/**
 * SAF 目录树文档 ID → 真实目录（纯 JVM，便于单测）。
 *
 * 文档 ID 形如 "primary:Documents/QAA"：冒号前为存储卷、冒号后为卷内相对路径。
 * 只接受不含 "." / ".." 的相对段，归一化后必须仍位于卷根内。
 */
internal object SafTreePath {

    fun resolve(docId: String, volumeDirectory: (String) -> File?): File {
        val separator = docId.indexOf(':')
        if (separator <= 0) throw IllegalArgumentException("无法解析授权目录标识：$docId")
        val volume = docId.substring(0, separator)
        val relative = docId.substring(separator + 1)
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw IllegalArgumentException("授权目录包含非法路径段：$docId")
        }
        val base = volumeDirectory(volume)?.canonicalFile
            ?: throw IllegalArgumentException("不支持的存储卷：$volume")
        val target = segments.fold(base) { current, segment -> File(current, segment) }.canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw IllegalArgumentException("授权目录越界：$docId")
        }
        return target
    }
}
