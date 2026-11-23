package com.thatgfsj.qaa.core.inference

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后端信号来源（docs/ARCHITECTURE.md 4.2 三源合一）。
 *
 * [LOCKED] 与 [CREATE_RESULT] 由请求/命中状态流直接承载（lockedBackend /
 * activeBackend），[LOGCAT] 是当前唯一需要旁证通道的一源，由 [BackendProbe] 产出。
 */
enum class BackendSignalSource {
    /** 显式锁定值（请求面）。 */
    LOCKED,

    /** create 成功档位（权威源）。 */
    CREATE_RESULT,

    /** logcat 解析结果（旁证源，可能缺失或命中历史日志）。 */
    LOGCAT,
}

/** 一次后端观测结果。 */
data class BackendSignal(
    val tier: ActiveBackend,
    val source: BackendSignalSource,
    val detail: String? = null,
)

/**
 * 可选的后端观测探针。0.10.0 没有公开的实际后端查询 API，logcat 解析只能作旁证
 * （docs/research/geniex-api.md §3.4）；实现失败必须返回 null，不得影响加载与生成。
 */
fun interface BackendProbe {
    suspend fun probe(): BackendSignal?
}

/**
 * GenieX logcat 行解析（tag GenieXSdk）。
 *
 * 已知格式（native 实现细节，跨版本可能变动）：
 * - "[JNI] [extract] compute_unit = npu, n_gpu_layers = -1 (from raw='npu')"
 * - "resolve_device failed (...), falling back to empty device"（空 device + ngl=-1
 *   即 llama.cpp 自身逐张量调度，按 HYBRID 旁证）
 */
object GenieXLogcatParser {

    private val COMPUTE_UNIT = Regex("""compute_unit\s*=\s*([A-Za-z0-9_]+)""")
    private const val EMPTY_DEVICE_FALLBACK = "falling back to empty device"

    /** 扫描全部行，最后一条命中优先（一次加载可能打印多条解析日志）。 */
    fun parse(lines: Sequence<String>): BackendSignal? {
        var signal: BackendSignal? = null
        for (line in lines) {
            signal = parseLine(line) ?: signal
        }
        return signal
    }

    fun parseLine(line: String): BackendSignal? {
        val match = COMPUTE_UNIT.find(line)
        if (match != null) {
            val tier = when (match.groupValues[1].lowercase()) {
                "npu" -> ActiveBackend.NPU
                "hybrid" -> ActiveBackend.HYBRID
                "gpu" -> ActiveBackend.GPU
                "cpu" -> ActiveBackend.CPU
                else -> null
            }
            if (tier != null) {
                return BackendSignal(tier, BackendSignalSource.LOGCAT, line.trim())
            }
        }
        if (line.contains(EMPTY_DEVICE_FALLBACK, ignoreCase = true)) {
            return BackendSignal(ActiveBackend.HYBRID, BackendSignalSource.LOGCAT, line.trim())
        }
        return null
    }
}

/**
 * logcat 探针：dump tag GenieXSdk 后交给 [GenieXLogcatParser]。
 *
 * Android 11+ 普通应用无 READ_LOGS 权限，dump 通常为空或抛错；此时返回 null，
 * 不影响 create 成功档位。-d 读取的是整个缓冲区，可能命中历史加载日志，因此结果
 * 只作旁证、不覆盖 create 成功档位；真机可观测性待 Issue #22 验证。
 */
class LogcatBackendProbe(
    private val timeoutMs: Long = 800L,
    private val runner: LogcatRunner = SystemLogcatRunner,
) : BackendProbe {

    override suspend fun probe(): BackendSignal? = withContext(Dispatchers.IO) {
        val dump = runCatching { runner.dump(timeoutMs) }.getOrNull() ?: return@withContext null
        GenieXLogcatParser.parse(dump.lineSequence())
    }
}

/** logcat 读取抽象；单测可注入固定输出。 */
fun interface LogcatRunner {
    suspend fun dump(timeoutMs: Long): String?
}

/** 通过 logcat 命令读取；受系统权限限制，失败时返回 null。 */
object SystemLogcatRunner : LogcatRunner {

    override suspend fun dump(timeoutMs: Long): String? {
        val executable = if (File("/system/bin/logcat").canExecute()) "/system/bin/logcat" else "logcat"
        val process = ProcessBuilder(executable, "-d", "-s", "GenieXSdk")
            .redirectErrorStream(true)
            .start()
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                null
            } else {
                process.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            process.destroy()
        }
    }
}
