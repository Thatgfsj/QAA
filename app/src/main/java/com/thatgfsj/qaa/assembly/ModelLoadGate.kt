package com.thatgfsj.qaa.assembly

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 模型载入闸门（#104）：把「引擎正在载入」这一窗口显式建模，避免两件事同时成立——
 * 顶栏先显示模型名（假装就绪），而用户此时提交的轮次又被「模型尚未就绪」拒绝。
 *
 * 语义：
 * - 载入（冷启动 initialize 与 selectModel 切档）经 [withLoad] 串行执行，期间 [isLoadInProgress] 为真；
 * - 轮次入口先 [awaitIdle] 等待，载入结束后自动继续，即「载入期 prompt 排队」而不是失败；
 * - 不持有 Android / 引擎引用：纯 kotlinx.coroutines 实现，可 JVM 单测。
 *
 * 加锁顺序约定：同时持有初始化锁时必须是 initMutex → 本闸门；且不得在闸门内执行整轮推理，
 * 避免把轮次时长也算进「载入中」。
 */
class ModelLoadGate {

    private val mutex = Mutex()

    @Volatile
    private var loading = 0

    /** 当前是否有载入在进行（仅供顶栏 / 回执展示；执行判据是 [awaitIdle]）。 */
    val isLoadInProgress: Boolean
        get() = loading > 0

    /** 串行执行一次载入；无论成功失败，结束后「载入中」标记都会清除。 */
    suspend fun <T> withLoad(block: suspend () -> T): T = mutex.withLock {
        loading += 1
        try {
            block()
        } finally {
            loading -= 1
        }
    }

    /** 等待进行中的载入结束（无载入时立即返回）：载入期排队的轮次在此等待。 */
    suspend fun awaitIdle() {
        while (isLoadInProgress) {
            mutex.withLock { /* 拿到锁说明当前载入已结束 */ }
        }
    }
}

/**
 * 载入期排队回执（#104）：与失败态「模型尚未就绪：…」区分，明确告知指令没有丢。
 */
internal fun queuedPromptNotice(name: String?): String {
    val target = name?.trim().orEmpty()
    return if (target.isEmpty()) {
        "模型载入中：指令已排队，载入完成后自动执行。"
    } else {
        "模型载入中（$target）：指令已排队，载入完成后自动执行。"
    }
}
