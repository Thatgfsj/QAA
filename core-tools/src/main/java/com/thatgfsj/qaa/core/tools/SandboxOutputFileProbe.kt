package com.thatgfsj.qaa.core.tools

import com.thatgfsj.qaa.core.inference.OutputFileCheck
import com.thatgfsj.qaa.core.inference.OutputFileProbe
import java.nio.file.Files

/**
 * 基于 [SandboxFs] 的落盘校验：沙盒相对路径 → 授权根内真实路径 → 存在且非空的普通文件。
 *
 * 路径越界、符号链接逃逸、文件缺失或为零字节均返回 [OutputFileCheck.Invalid]。
 */
class SandboxOutputFileProbe(private val sandbox: SandboxFs) : OutputFileProbe {

    override suspend fun inspect(sandboxPath: String): OutputFileCheck = try {
        val path = sandbox.resolve(sandboxPath).absolutePath
            ?: return OutputFileCheck.Invalid("输出路径不在文件系统授权根内: " + sandboxPath)
        when {
            !Files.exists(path) -> OutputFileCheck.Invalid("输出文件不存在: " + sandboxPath)
            !Files.isRegularFile(path) -> OutputFileCheck.Invalid("输出路径不是普通文件: " + sandboxPath)
            else -> {
                val size = Files.size(path)
                if (size > 0L) {
                    OutputFileCheck.Valid(size)
                } else {
                    OutputFileCheck.Invalid("输出文件为空: " + sandboxPath)
                }
            }
        }
    } catch (e: SandboxPathException) {
        OutputFileCheck.Invalid("输出路径不在沙盒授权根内: " + sandboxPath)
    } catch (e: Exception) {
        OutputFileCheck.Invalid("输出文件校验失败: " + (e.message ?: e.javaClass.simpleName))
    }
}
