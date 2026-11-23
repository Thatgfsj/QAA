package com.thatgfsj.qaa.core.inference

import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.ModelManagerWrapper.PullEvent.Completed as SdkCompleted
import com.geniex.sdk.ModelManagerWrapper.PullEvent.Error as SdkError
import com.geniex.sdk.ModelManagerWrapper.PullEvent.Progress as SdkProgress
import com.geniex.sdk.bean.FileProgress
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ModelPullClient 单测：URL 归一化 + 事件投影 + 取消/断点续传语义。 */
class ModelPullClientTest {

    @Test
    fun HF标准URL与裸模型名归一化() {
        val url = parseModelPullTarget("https://huggingface.co/liodon-ai/Supra2.5-Pro-Instruct-GGUF")
            .getOrThrow()
        assertEquals(HubSource.HUGGINGFACE, url.hub)
        assertEquals("liodon-ai/Supra2.5-Pro-Instruct-GGUF", url.modelName)
        assertNull(url.fileName)
        assertNull(url.precision)
        assertEquals(HubSource.AUTO, parseModelPullTarget("liodon-ai/Supra2.5-Pro-Instruct-GGUF").getOrThrow().hub)
    }

    @Test
    fun HF_blobURL_取文件名精度并透传拉取参数() = runTest {
        val target = parseModelPullTarget(
            "https://huggingface.co/liodon-ai/Supra2.5-Pro-Instruct-GGUF" +
                "/blob/main/Supra2.5-Pro-Instruct-Q4_0.gguf",
        ).getOrThrow()
        assertEquals("Supra2.5-Pro-Instruct-Q4_0.gguf", target.fileName)
        assertEquals("Q4_0", target.precision)
        val gateway = FakeGateway(events = listOf(SdkCompleted))
        ModelPullClient(gateway, Dispatchers.Unconfined).pull(target, hfToken = "hf_x").toList()
        val input = gateway.inputs.single()
        assertEquals("liodon-ai/Supra2.5-Pro-Instruct-GGUF", input.model_name)
        assertEquals(HubSource.HUGGINGFACE, input.hub)
        assertEquals("Q4_0", input.precision)
        assertEquals("hf_x", input.hf_token)
    }

    @Test
    fun ModelScope_前缀与resolveURL归一化() {
        val plain = parseModelPullTarget("https://modelscope.cn/models/qwen/Qwen2.5-7B-Instruct")
            .getOrThrow()
        assertEquals(HubSource.MODELSCOPE, plain.hub)
        assertEquals("qwen/Qwen2.5-7B-Instruct", plain.modelName)
        assertNull(plain.fileName)
        val file = parseModelPullTarget(
            "https://modelscope.cn/models/qwen/Qwen2.5-7B-Instruct/resolve/master/Qwen2.5-7B-IQ4_XS.gguf",
        ).getOrThrow()
        assertEquals("Qwen2.5-7B-IQ4_XS.gguf", file.fileName)
        assertEquals("IQ4_XS", file.precision)
    }

    @Test
    fun 非法来源_解析失败且拉取不调用SDK() = runTest {
        assertTrue(parseModelPullTarget("   ").isFailure)
        assertTrue(parseModelPullTarget("ftp://huggingface.co/a/b").isFailure)
        assertTrue(parseModelPullTarget("https://example.com/a/b").isFailure)
        assertTrue(parseModelPullTarget("https://huggingface.co/only-org").isFailure)
        assertTrue(parseModelPullTarget("only-org").isFailure)
        val gateway = FakeGateway(events = listOf(SdkCompleted))
        val event = ModelPullClient(gateway, Dispatchers.Unconfined).pull("not a model").toList().single()
        assertEquals(GenieXPullErrorCodes.INVALID_INPUT, (event as ModelPullEvent.Failed).code)
        assertFalse(event.resumable)
        assertTrue(gateway.inputs.isEmpty())
    }

    @Test
    fun 进度事件_多文件聚合与总长未知() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                SdkProgress(listOf(FileProgress("a.gguf", 10, 100), FileProgress("b.gguf", 20, 100))),
                SdkCompleted,
            ),
        )
        val events = ModelPullClient(gateway, Dispatchers.Unconfined).pull("org/repo").toList()
        assertEquals(ModelPullEvent.Progress(30, 200, 0.15f), events.first())
        assertEquals(ModelPullEvent.Completed("org/repo", null), events.last())
        val unknown = FakeGateway(events = listOf(SdkProgress(listOf(FileProgress("a.gguf", 5, -1)))))
        val progress = ModelPullClient(unknown, Dispatchers.Unconfined).pull("org/repo").toList().single()
        assertEquals(ModelPullEvent.Progress(5, null, null), progress)
    }

    @Test
    fun 失败事件_按错误码判定可续传且异常兜底() = runTest {
        val gateway = FakeGateway(
            events = listOf(
                SdkError(GenieXPullErrorCodes.NETWORK, "网络中断"),
                SdkError(GenieXPullErrorCodes.AUTH, "token 无效"),
            ),
            failure = IllegalStateException("native 中断"),
        )
        val failures = ModelPullClient(gateway, Dispatchers.Unconfined).pull("org/repo").toList()
            .filterIsInstance<ModelPullEvent.Failed>()
        assertEquals(3, failures.size)
        assertTrue(failures[0].resumable)
        assertFalse(failures[1].resumable)
        assertEquals(GenieXPullErrorCodes.AUTH, failures[1].code)
        assertEquals(GenieXPullErrorCodes.UNKNOWN, failures[2].code)
        assertTrue(failures[2].resumable)
    }

    @Test
    fun 完成事件_返回getPaths路径() = runTest {
        val path = "/data/geniex/models/org/repo/model.gguf"
        val gateway = FakeGateway(events = listOf(SdkCompleted), paths = modelPaths(path))
        val event = ModelPullClient(gateway, Dispatchers.Unconfined).pull("org/repo").toList().single()
        assertEquals(ModelPullEvent.Completed("org/repo", path), event)
    }

    @Test
    fun 取消传播_上游取消且不发失败事件() = runTest {
        val gateway = CancellableGateway()
        val events = mutableListOf<ModelPullEvent>()
        val job = launch(Dispatchers.Unconfined) {
            ModelPullClient(gateway, Dispatchers.Unconfined).pull("org/repo").collect { events += it }
        }
        job.cancelAndJoin()
        assertTrue(gateway.cancelled)
        assertEquals(1, events.size)
        assertTrue(events.single() is ModelPullEvent.Progress)
    }

    @Test
    fun 中断后续传_第二次拉取从已下载偏移继续() = runTest {
        val gateway = ResumableGateway()
        val client = ModelPullClient(gateway, Dispatchers.Unconfined)
        val failure = client.pull("org/repo").toList()
            .filterIsInstance<ModelPullEvent.Failed>().single()
        assertTrue(failure.resumable)
        assertEquals(50L, gateway.downloaded)
        val resumed = client.pull("org/repo").toList()
            .filterIsInstance<ModelPullEvent.Progress>().last()
        assertEquals(100L, resumed.bytes)
        assertEquals(1f, resumed.percent)
        assertEquals(2, gateway.calls)
    }
}
private fun modelPaths(path: String): ModelPaths = ModelPaths(
    model_path = path,
    model_dir = path.substringBeforeLast('/'),
    model_name = "org/repo",
    runtime_id = "llama_cpp",
    model_type = ModelType.LLM,
    mmproj_path = null,
    tokenizer_path = null,
)

private class FakeGateway(
    private val events: List<ModelManagerWrapper.PullEvent> = emptyList(),
    private val paths: ModelPaths? = null,
    private val failure: Throwable? = null,
) : GenieXModelGateway {
    val inputs = mutableListOf<ModelPullInput>()
    override suspend fun getPaths(modelName: String): ModelPaths? = paths
    override suspend fun list(): List<String> = emptyList()
    override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> {
        inputs += input
        return flow {
            events.forEach { emit(it) }
            failure?.let { throw it }
        }
    }
}

private class CancellableGateway : GenieXModelGateway {
    var cancelled = false
    override suspend fun getPaths(modelName: String): ModelPaths? = null
    override suspend fun list(): List<String> = emptyList()
    override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> = flow {
        emit(SdkProgress(listOf(FileProgress("a.gguf", 10, 100))))
        try {
            awaitCancellation()
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        }
    }
}

private class ResumableGateway : GenieXModelGateway {
    var calls = 0
    var downloaded = 0L
    override suspend fun getPaths(modelName: String): ModelPaths? = null
    override suspend fun list(): List<String> = emptyList()
    override fun pull(input: ModelPullInput): Flow<ModelManagerWrapper.PullEvent> = flow {
        calls++
        emit(SdkProgress(listOf(FileProgress("m.gguf", 50, 100))))
        downloaded = 50
        if (calls == 1) {
            emit(SdkError(GenieXPullErrorCodes.NETWORK, "网络中断"))
        } else {
            emit(SdkProgress(listOf(FileProgress("m.gguf", 100, 100))))
            emit(SdkCompleted)
        }
    }
}
