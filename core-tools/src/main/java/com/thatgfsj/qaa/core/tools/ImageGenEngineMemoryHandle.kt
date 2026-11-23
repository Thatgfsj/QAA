package com.thatgfsj.qaa.core.tools

import com.thatgfsj.qaa.core.inference.ImageGenHandle
import kotlinx.coroutines.CancellationException

/**
 * 把 [ImageGenEngine] 适配为内存互斥状态机使用的 [ImageGenHandle]。
 *
 * 适配器跟踪画图引擎的驻留状态与引用计数，供状态机做互斥断言；
 * 卸载与销毁保证幂等，异常统一转成 [Result.failure]（取消异常原样抛出）。
 */
class ImageGenEngineMemoryHandle(private val engine: ImageGenEngine) : ImageGenHandle {

    private var loaded: Boolean = false
    private var references: Int = 0

    override val isLoaded: Boolean get() = loaded

    override val referenceCount: Int get() = references

    override suspend fun load(modelPath: String): Result<Unit> = try {
        engine.load(modelPath)
        loaded = true
        references = 1
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun generate(prompt: String, outputPath: String): Result<String> = try {
        engine.generate(prompt, outputPath)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun unload(): Result<Unit> = try {
        engine.unload()
        loaded = false
        references = 0
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun destroy() {
        try {
            if (loaded) engine.unload()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 图外恢复要求尽力释放；卸载失败不阻断后续销毁与重建。
        } finally {
            loaded = false
            references = 0
        }
    }
}
