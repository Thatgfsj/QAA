package com.thatgfsj.qaa.core.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 轮次输入来源（设计文档 7.2/7.3）。 */
enum class TurnInputKind { STEER, FOLLOW_UP }

/** 一条待执行的轮次输入。 */
data class QueuedTurnInput(val text: String, val kind: TurnInputKind) {

    val isSteer: Boolean get() = kind == TurnInputKind.STEER
}

/** 轮末裁决结果（设计文档 7.3 注入点：轮次结束 / 转向边界）。 */
sealed interface TurnAction {

    /**
     * 本轮在工具步边界被 steer 打断，且转向文本已由 [AgentLoop] 以 tags=[steer]
     * 入树：直接续跑生成即可，不得重复追加 user 节点。
     */
    object ResumeAfterSteer : TurnAction

    /** 以一条完整新 user 轮次执行：steer 打 tags=[steer]，followUp 不带标签。 */
    data class NewTurn(val input: QueuedTurnInput) : TurnAction
}

/**
 * 确认挂起期间的转向信号（设计文档 7.1 与附录 A.1 L7）。
 *
 * [AgentLoop] 在本类与 [ConfirmationGate] 之间做竞速：steer 先到即按 Deny 语义处理，
 * 当前工具不执行；转向文本不在此消费，留给工具步边界统一入树。
 */
interface ConfirmationInterrupt {

    /** 队列中是否已有待消费的 steer（确认挂起前即到达）。 */
    suspend fun hasPendingSteer(): Boolean

    /** 挂起等待 steer 到达并返回其文本；peek 语义，不消费队列。 */
    suspend fun awaitSteer(): String
}

/**
 * 运行中干预队列（设计文档第 7 章）：双队列，steer（打断转向）恒优先于
 * followUp（轮末衔接）。
 *
 * 消费点统一为「轮次结束 / 转向边界」（7.3）：
 * - [interruptCheck]：AgentLoop 的工具步边界回调，取走一条 steer 并置位边界转向标记；
 *   Loop 随即以 tags=[steer] 入树并停止本轮剩余工具步与后续生成（7.1）。
 * - [nextTurnAction]：每轮结束后由装配层调用，先处理边界转向（续跑生成），再按
 *   steer → followUp 的严格优先序各取一条，空队列返回 null（7.3）。
 *
 * 覆盖/容量语义（按 7.3 裁决）：**不覆盖、不设上限**。7.3 要求「同队列内严格
 * FIFO」且「多条 steer 逐条独立成轮」；新 steer 覆盖旧 steer 会静默丢失用户指令，
 * 与两条不变量同时冲突。队列为进程内语义、不持久化（7.2 明确取舍），写入速率受
 * 人工操作限制，无需容量兜底。
 */
class SteerQueue : ConfirmationInterrupt {

    private val mutex = Mutex()
    private val steerQueue = ArrayDeque<String>()
    private val followUpQueue = ArrayDeque<String>()

    /** steer 入队唤醒信号：只做「有 steer 到达」通知，长度取 1 且丢弃旧值即可。 */
    private val steerSignal = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 工具步边界已消费 steer、等待装配层续跑生成（[TurnAction.ResumeAfterSteer]）。 */
    private var interruptedAtBoundary = false

    /**
     * /steer：入队一条转向指令，空白文本忽略。
     *
     * 工具执行中调用只是置队，真正的打断点在当前单个工具完成后的工具步边界
     * （工具执行是原子段、不可中断，7.1）；确认挂起中则由 [awaitSteer] 唤醒
     * [AgentLoop] 的竞速，等价 Deny + 转向。
     */
    suspend fun requestSteer(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock { steerQueue.addLast(trimmed) }
        steerSignal.trySend(Unit)
    }

    /** Follow-up：追加一条普通指令；不打断当前轮，轮次自然结束后 FIFO 衔接（7.2）。 */
    suspend fun offerFollowUp(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock { followUpQueue.addLast(trimmed) }
    }

    /**
     * [AgentLoop] interruptCheck 适配：工具步边界消费一条 steer，返回 null 表示不打断。
     * 返回非 null 时 Loop 必然将该文本入树并终止本轮，故此处同步置位续跑标记。
     */
    suspend fun interruptCheck(): String? = mutex.withLock {
        val text = steerQueue.removeFirstOrNull() ?: return@withLock null
        interruptedAtBoundary = true
        text
    }

    override suspend fun hasPendingSteer(): Boolean = mutex.withLock { steerQueue.isNotEmpty() }

    /**
     * 挂起等待 steer 到达；返回队首文本但不消费。陈旧唤醒信号可能让本方法空转一次，
     * 循环重新检查队列即可（多消费者下仍由 [interruptCheck] 单点消费）。
     */
    override suspend fun awaitSteer(): String {
        while (true) {
            mutex.withLock { steerQueue.firstOrNull() }?.let { return it }
            steerSignal.receive()
        }
    }

    /**
     * 轮末裁决（7.3）：边界转向 → steer → followUp，三者皆空返回 null。
     * 同一时刻只产出一个动作，保证多条 steer 逐条独立成轮（Q4）。
     */
    suspend fun nextTurnAction(): TurnAction? = mutex.withLock {
        if (interruptedAtBoundary) {
            interruptedAtBoundary = false
            return@withLock TurnAction.ResumeAfterSteer
        }
        steerQueue.removeFirstOrNull()?.let {
            return@withLock TurnAction.NewTurn(QueuedTurnInput(it, TurnInputKind.STEER))
        }
        followUpQueue.removeFirstOrNull()?.let {
            return@withLock TurnAction.NewTurn(QueuedTurnInput(it, TurnInputKind.FOLLOW_UP))
        }
        null
    }

    /** 是否仍有待消费输入（不含等待续跑的边界转向标记）。 */
    suspend fun hasQueuedInput(): Boolean = mutex.withLock {
        steerQueue.isNotEmpty() || followUpQueue.isNotEmpty()
    }

    /** 清空两队列与边界标记（消费方取消 / 会话重置）。 */
    suspend fun clear() {
        mutex.withLock {
            steerQueue.clear()
            followUpQueue.clear()
            interruptedAtBoundary = false
        }
        // 丢弃陈旧唤醒信号，避免 clear 后的下一次 awaitSteer 空转。
        while (steerSignal.tryReceive().isSuccess) {
            // 仅消费信号，无附带数据。
        }
    }
}

/**
 * 轮次驱动（设计文档 7.2/7.3）：首轮结束后按 [SteerQueue.nextTurnAction] 的裁决
 * 逐条衔接 steer / followUp 新轮次，直至两队列皆空。
 *
 * 装配层接线：AgentLoop 的 interruptCheck 接 [SteerQueue.interruptCheck]，构造参数的
 * confirmationInterrupt 接同一队列，随后用本类驱动轮次即可获得完整的运行中干预语义。
 *
 * 取消传播：消费方协程被取消时清空队列（轮次不再继续，残留指令不应在下次驱动时
 * 以陈旧语义执行），随后继续向上传播 CancellationException（附录 A.1 L9 终止不变量）。
 */
class SteerTurnDriver(private val queue: SteerQueue) {

    /**
     * 驱动轮次：[initialText] 为 null 表示空闲态直接消费队列（无首轮输入）。
     * 每条 steer 独立成轮：边界转向续跑生成，轮末 steer 以 tags=[steer] 新轮注入。
     */
    suspend fun drive(loop: AgentLoop, initialText: String? = null) {
        try {
            if (initialText != null) loop.runTurn(initialText)
            while (true) {
                when (val action = queue.nextTurnAction() ?: break) {
                    TurnAction.ResumeAfterSteer -> loop.resumeTurn()
                    is TurnAction.NewTurn -> loop.runTurn(
                        action.input.text,
                        tags = if (action.input.isSteer) listOf(AgentLoop.TAG_STEER) else emptyList(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            // 清理本身不可被取消吞掉，否则取消瞬间可能残留队列。
            withContext(NonCancellable) { queue.clear() }
            throw cancelled
        }
    }
}
