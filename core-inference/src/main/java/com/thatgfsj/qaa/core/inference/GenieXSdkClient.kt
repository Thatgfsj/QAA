package com.thatgfsj.qaa.core.inference

import android.content.Context
import com.geniex.sdk.GenieXSdk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GenieX SDK 初始化封装（Issue #5）。
 *
 * GenieXSdk.init 内部做两件事：注册 llama_cpp / qairt 原生插件，以及把
 * filesDir/geniex 交给模型管理器初始化（docs/research/geniex-api.md §2.1）。
 * init 自身已按插件/模型管理器分别幂等，但并发调用仍可能重复注册，因此这里用
 * [Mutex] 串行化，并且只在成功后置位；失败允许下次重试。
 *
 * init 的回调是同步调用的（成功或失败各一次），故用 [CompletableDeferred] 转成
 * 挂起结果；init 抛异常（如 native 库缺失）同样收敛为 [Result.failure]。
 */
class GenieXSdkClient(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SdkInitializer {

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    override suspend fun initialize(): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            if (initialized) {
                Result.success(Unit)
            } else {
                val outcome = CompletableDeferred<Result<Unit>>()
                try {
                    GenieXSdk.getInstance().init(
                        context,
                        object : GenieXSdk.InitCallback {
                            override fun onSuccess() {
                                outcome.complete(Result.success(Unit))
                            }

                            override fun onFailure(reason: String) {
                                outcome.complete(Result.failure(GenieXSdkInitException(reason)))
                            }
                        },
                    )
                } catch (error: Throwable) {
                    outcome.complete(Result.failure(error))
                }
                outcome.await().also { result ->
                    if (result.isSuccess) initialized = true
                }
            }
        }
    }
}

/** SDK 初始化失败：插件缺失、注册失败或模型管理器初始化失败。 */
class GenieXSdkInitException(reason: String) :
    IllegalStateException("GenieX SDK 初始化失败：" + reason)
