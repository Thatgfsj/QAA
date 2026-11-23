# GenieX Android SDK API 调研报告（com.qualcomm.qti:geniex-android:0.10.0）

> **用途**：为 QAA `core-inference`（LlmEngine / BackendFallbackManager / InferenceMetrics / 模型下载器）与 `model pull` 命令提供 GenieX Android SDK 的接口事实依据。
> **调研人**：geniex-researcher（共享任务 task-1）｜分支 `docs/geniex-research`｜日期 2026-11-24
> **结论可信度**：所有事实均标注来源；与 QAA 架构假设冲突处单独点名；无法离线确证项集中列于 §13 待验证清单。

## 0. 调研方法与来源分级

| 标注 | 来源 | 可靠性说明 |
| --- | --- | --- |
| 【AAR】 | `repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0/geniex-android-0.10.0.aar`（89 MB，已下载解包） | **第一手**。对 `classes.jar`（88 个类）执行 `javap` 提取公开签名；AAR 内 `AndroidManifest.xml`、`jni/arm64-v8a` 53 个原生库逐一核对。0.10.0 的 sources.jar / javadoc.jar 仅含 40 字节 README 占位，故字节码为唯一版本锁定事实 |
| 【GH-main】 | `github.com/qualcomm/GenieX`（main 分支 `bindings/android/app/src/main/java/com/geniex/sdk/`） | Kotlin 源码与 KDoc。已抽查 ModelConfig / GenerationConfig / SamplerConfig / GenieXSdk / jni.Llm / LlmWrapper 的 AAR javap 参数与 main 源码**逐项一致**，判定 main ≈ 0.10.0（发布日 2026-10-28，见 maven-metadata `lastUpdated`） |
| 【JNI】 | 同仓库 `bindings/android/app/src/main/cpp/`（jniutils.cpp / llm_bridge_jni.cpp / jni_cb.cpp）与 `sdk/src/device.cpp`、`sdk/model-manager/src/ffi/types.rs` | 后端别名解析与错误码的**实现级**事实（文档矛盾以此为准） |
| 【DOC】 | `geniex.aihub.qualcomm.com` 官方文档站（.md 直取）：install / quickstart / api-reference / platforms / models-supported / faq | 官方口径；与源码冲突处在文中点名 |
| 【SAMPLE】 | `github.com/qualcomm/ai-hub-apps`（release 分支 `geniex_chat_android`，稀疏克隆实测） | 官方参考 App；注意其钉住 `geniex-android:0.3.5`（落后两个大版本），借鉴需防 API 漂移 |
| 【QAIRT-本机】 | `/home/thatgfsj/Downloads/v2.52.0.261104/qairt/2.52.0.261104` | 本机 QAIRT SDK 实测目录结构 |

版本事实：Maven Central 上 `geniex-android` 共 39 个版本（0.0.1 → 0.10.0），`latest = release = 0.10.0`，`lastUpdated = 20261028`。【AAR】

---

## 1. SDK 坐标、AAR 内容与分层架构

### 1.1 坐标与许可

```kotlin
// settings.gradle.kts: repositories { google(); mavenCentral() }
dependencies { implementation("com.qualcomm.qti:geniex-android:0.10.0") }
```

- pom 描述："GenieX Android AAR - multi-platform AI inference runtime (Snapdragon / Hexagon)"；许可 BSD-3-Clause + Qualcomm Terms of Use；scm 指向 `github.com/qualcomm/GenieX`。【AAR】
- AAR 自带全部 arm64-v8a 原生库，**应用侧无需 NDK/CMake**。【DOC-install】

### 1.2 classes.jar 包结构（88 类，包名 `com.geniex.sdk`，非 groupId）【AAR】

| 分层 | 类 | 职责 |
| --- | --- | --- |
| 入口 | `GenieXSdk`（单例，`System.loadLibrary("npu_jni")`） | `init(context, callback)`、插件注册、`setQairtRuntimePath`、`getSdkVersion` |
| 公开 API | `LlmWrapper` / `VlmWrapper`（均 `Closeable`，Builder 模式） | 高层 Kotlin API：构造/模板/流式生成/取消/销毁 |
| 高层 API | `ModelManagerWrapper`（object） | 模型下载/导入/清单/路径解析（`geniex_model_*` FFI 的 Kotlin 封装） |
| JNI 声明 | `jni.Llm` / `jni.Vlm` / `jni.ModelManager` | `external fun`（native 句柄为 `Long`） |
| 数据类 | `bean.*` 33 个（ChatMessage、ModelConfig、LlmCreateInput、GenerationConfig、SamplerConfig、ProfilingData、LlmStreamResult、ModelPullInput、ModelPaths、ModelQuery、ComputeUnitValue、RuntimeIdValue、HubSource、ModelType、ToolCall、ChatTemplateGrammar…） | 全部为 Kotlin data class / enum |
| 回调 | `bean.LLMTokenCallback`、`callback.DownloadProgressCallback`、`GenieXSdk$InitCallback` | 见 §5/§8/§2 |

### 1.3 分层架构（官方 JNI 桥文档）【GH-main bindings/android/README.md】

```text
L4 Kotlin 公开 API   LlmWrapper/ModelManagerWrapper（协程 + Flow）
L3 JNI 声明         jni.Llm 等 external fun
L2 C++ JNI 桥       llm_bridge_jni.cpp → Java_com_geniex_sdk_jni_Llm_*
L1 C 核心           libgeniex.so 的 ml_* C API（ml.h），插件制后端
```

- 日志统一走 logcat，**tag = `GenieXSdk`**（VERBOSE~ERROR），`adb logcat -s GenieXSdk` 即可观测 SDK 行为。【GH-main bindings/android/README.md】

### 1.4 AAR 原生库清单（arm64-v8a，53 个，共 89 MB）【AAR】

| 组 | 库 | 说明 |
| --- | --- | --- |
| JNI 桥 | `libnpu_jni.so` | GenieXSdk.kt 显式加载 |
| GenieX 核心 | `libgeniex.so` `libgeniex_core.so` `libgeniex-proc.so` `libgeniex-proc-vision.so` `libgeniex_vlm.so` | ml_* C API |
| 插件 | `libgeniex_plugin_llama_cpp.so` `libgeniex_plugin_qairt.so` | 对应 `RuntimeIdValue.LLAMA_CPP / QAIRT`，`init()` 时按名注册 |
| llama.cpp 栈 | `libllama.so` `libllama-common.so` `libggml*.so`（base/cpu/**hexagon**/**htp-v73/75/79/81/85**/**opencl**）`libmtmd.so` `libomp.so` | GGUF 路径；ggml-hexagon+htp-vXX 即 Hexagon NPU 后端，ggml-opencl 即 Adreno GPU 后端 |
| QNN/QAIRT 栈 | `libQnnHtp.so` `libQnnHtpV79/81.so` `libQnnHtpPrepare.so`（85 MB）`libQnnHtpV79/81Skel/Stub.so` `libQnnSystem.so` `libQnnIr.so` `libQnnHta.so` `libhta_hexagon_runtime_*.so` `libcalculator*.so` 等 | AI Engine Direct（qairt 插件）所需；Skel/Stub 为 FastRPC 双侧桩 |
| Hexagon 覆盖 | htp-v73/75/79/81/85 | 对应骁龙 8 Gen 2（v73）→ 8 Elite Gen 5（v85）区间 |

AAR 内 `AndroidManifest.xml` 全文：`package="com.geniex.sdk"`、`minSdkVersion=27`、三个 `uses-native-library`（`libadsprpc.so` / `libcdsprpc.so` / `libOpenCL.so`，均 `required="false"`），**无任何 uses-permission**。【AAR】

---

## 2. 初始化与引擎构造（问题 1 前半）

GenieX 没有 `LlmEngine` 这个类名；等价物是 **`LlmWrapper`**（引擎句柄包装）。

### 2.1 SDK 初始化（幂等，Activity.onCreate 安全）【GH-main GenieXSdk.kt】【AAR】

```kotlin
GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
    override fun onSuccess() { /* 插件注册 + 模型管理器就绪 */ }
    override fun onFailure(reason: String) { /* reason 拼接各失败项 */ }
})
```

`init` 内部两件事（源码级事实）：
1. 到 `context.applicationInfo.nativeLibraryDir` 找 `libgeniex_plugin_llama_cpp.so` / `libgeniex_plugin_qairt.so` 并 `registerPlugin(path)`；缺失或注册失败 → 记入 failure。
2. 创建 `File(context.filesDir, "geniex")` 并 `ModelManager().init(dataDir)`；返回码 0 或 `-100008`（ALREADY_INITIALIZED）视为成功，其余记入 failure。

### 2.2 引擎构造（Builder + Result 语义）【AAR javap ≡ GH-main LlmWrapper.kt】

```kotlin
val llm = LlmWrapper.builder()
    .llmCreateInput(LlmCreateInput(
        model_path     = paths.model_path,     // 必填
        tokenizer_path = null,               // 可空，GGUF 自含
        config         = ModelConfig(nCtx = 4096),
        runtime_id     = RuntimeIdValue.LLAMA_CPP.value,  // "llama_cpp" | "qairt" | null
        compute_unit   = null,               // 见 §3
    ))
    .dispatcher(Dispatchers.IO)              // 可选，默认 Dispatchers.IO
    .build()                                 // suspend, Result<LlmWrapper>
    .getOrThrow()                            // 或 onSuccess/onFailure 分支
```

- `build(): Result<LlmWrapper>` 为 suspend；`model_path` 缺失抛 `IllegalArgumentException`；native `create` 失败以异常进 `Result.failure`（§9）。
- **`LlmCreateInput` 完整字段**（实现 `CreateInputBase`）：`model_path: String`、`tokenizer_path: String?`、`config: ModelConfig`、`runtime_id: String?`、`compute_unit: String?`。【GH-main】
- **`ModelConfig` 全部 15 字段**（AAR javap 与源码逐参一致）：`nCtx=2048`（0=用模型默认）、`nThreads=8`、`nThreadsBatch=8`、`nBatch=2048`、`nUBatch=512`、`nSeqMax=1`、`nGpuLayers=-1`（-1=全部层；cpu 被强制改 0，gpu/npu/hybrid 透传，qairt 忽略）、`chat_template_path=""`、`chat_template_content=""`、投机解码 5 字段（`spec_type` 取值 "draft-mtp"/"draft-eagle3"/"draft-simple"/"ngram-*"、`spec_draft_model`、`spec_n_max`、`spec_n_min`、`spec_p_min`）、`power_mode=""`（HTP 电源模式 "low_power_saver".."burst"，默认 burst，qairt 与 llama_cpp 共享）。【AAR+GH-main】
- VLM 同构：`VlmCreateInput(model_path, mmproj_path, config, runtime_id, compute_unit)` + `VlmWrapper`（`injectMediaPathsToConfig`、`getCapabilities`）。

### 2.3 runtime_id 的两个运行时【DOC-api-reference】

| `runtime_id` | 引擎 | 模型格式 | 计算单元 |
| --- | --- | --- | --- |
| `"llama_cpp"` | llama.cpp + GGML Hexagon 后端 | GGUF | Hexagon NPU / Adreno GPU / CPU |
| `"qairt"` | Qualcomm AI Engine Direct | AI Hub 预编译 bundle | **仅 NPU** |
| `null` | SDK 依据模型路径/清单自选 | — | — |

选型口径：GGUF 一律 `llama_cpp`；AI Hub bundle 一律 `qairt`。`ModelPaths.runtime_id` 为权威值（"authoritative — prefer over UI selection"），QAA 的模型管理应以 getPaths 返回值驱动 UI。【DOC】

---

## 3. 计算后端选择、默认值与"降级链"事实（问题 1 后半）

### 3.1 别名表（实现级，`sdk/src/device.cpp`）【JNI】

```text
kAliasCPU="cpu"  kAliasGPU="gpu"  kAliasNPU="npu"  kAliasHybrid="hybrid"
空 / "auto"  → npu（GENIEX_CPU_ONLY 构建除外 → cpu）   ← 两运行时默认均为 npu
npu   → device_id="HTP0"（固定单 HTP 会话）
gpu   → Adreno OpenCL（libggml-opencl.so）
cpu   → n_gpu_layers 强制 0
hybrid→ device_id 置空 + ngl=-1：llama.cpp 逐张量 HTP+CPU 调度（官方称"快速路径"）
显式设备列表（如 "HTP0,HTP1"、"GPUOpenCL"）原样透传（llama_cpp）
```

- ComputeUnitValue 枚举：`CPU("cpu")` `GPU("gpu")` `NPU("npu")` `HYBRID("hybrid")`。`compute_unit` 字符串会被 JNI 经 `geniex_resolve_device` 翻译为 `(device_id, n_gpu_layers)`；解析结果与 warning 会打 logcat（`[JNI] [extract] compute_unit = %s, n_gpu_layers = %d (from raw='%s')`）。
- **官方性能口径**：NPU（HTP0 固定）"deterministic but slower than HYBRID on LLM workloads"；HYBRID（HTP+CPU 逐张量）是 Snapdragon 上的快速路径，**需显式传入** `hybrid`。【GH-main ComputeUnitValue KDoc + DOC-platforms/FAQ】
- **默认值勘误（重要）**：`InputPluginBase.kt` 的 KDoc 写 "null → HYBRID(llama_cpp)"，与 device.cpp 实现、官方三处文档（platforms/FAQ/api-reference）均矛盾。**以 device.cpp 为准：null → npu**。0.10.0 二进制未逐指令反汇编，列入 §13 待验证。

### 3.2 qairt 的硬约束（AI Engine Direct）【DOC-platforms/api-reference】

- NPU-only：传 `cpu`/`gpu` 不报错，**带 warning 强转 npu**。
- bundle 的精度、上下文长度、KV cache 均为编译期固化：`nGpuLayers != 0` 与 `nCtx != 0` 会被以 `PARAM_NOT_SUPPORTED` 拒绝；只能调 `maxTokens` / `enable_thinking`。

### 3.3 精度决定落点（GGUF 路径）【DOC-models/supported】

| 精度 | 落点 | 官方注 |
| --- | --- | --- |
| **Q4_0（默认）** | **Hexagon NPU** | "Best NPU support in llama.cpp. Recommended for most models." |
| Q8_0 / F16 | GPU / CPU | 质量更好但约 2×~8× 体积 |
| Q4_K_M、Q5_K_M 等 K-quants | GPU / CPU | "Not optimized for Hexagon NPU" |

→ **QAA 冒烟模型选 Q4_0 GGUF 的决策被官方精度表直接支持**。

### 3.4 如何感知"实际命中后端"

**0.10.0 没有公开的"查询当前生效后端"API**（GenieXSdk / jni.Llm / LlmWrapper 全签名中无此方法；ProfilingData 亦无 device 字段）。可行的三级方案：

1. **显式锁定即确定**：`compute_unit` 传 `npu/gpu/cpu/hybrid`，语义由 §3.1 别名表保证（QAA 的 `backend [auto|npu|hybrid|cpu]` 锁定命令建议映射到显式别名）。
2. **logcat 观测**：tag `GenieXSdk` 的 `[JNI] [extract] compute_unit = ...`、`resolve_device: <warning>`、create/destroy 调试日志；实现位于 llm_bridge_jni.cpp / jniutils.cpp，版本升级可能变动格式（列 §13 待验证）。降级场景中 `resolve_device` 失败会打 `geniex_resolve_device failed (rc=%d), falling back to empty device`——empty device + ngl=-1 即落到 llama.cpp 自身调度（逐张量 NPU/CPU 混合）。
3. **性能侧写**：`ProfilingData.prefillSpeed / decodingSpeed`（§6）——NPU/GPU/CPU 的 tok/s 差一个数量级，可作运行期推断与展示（QAA 状态栏可同时展示锁定值 + 实测速度）。

### 3.5 对 QAA 降级链（NPU→Hybrid→CPU）的映射结论

- GenieX **没有文档化的"失败自动逐级回退"链**：`qairt` 的 cpu/gpu 请求会被强转 npu；`llama_cpp` 的"回退"本质是 ①加载期按精度选落点（§3.3），②hybrid 模式下逐张量把不支持的算子留在 CPU。
- 因此 QAA 的 `BackendFallbackManager` 需要**自实现 create 级降级链**：按 §12 建议顺序尝试 `create`，`Result.failure` 即换下一档。
- **语义冲突点名**：QAA `ComputeBackend.HYBRID` 注释为"Adreno GPU"，而 GenieX 的 `hybrid` = HTP+CPU 逐张量（GPU 是独立的 `gpu` 别名）。建议 Lead 决策：要么把 QAA 的 HYBRID 析构改名为 GPU 并映射 `"gpu"`，要么把 QAA 的 AUTO 映射 `"hybrid"`（性能快速路径）、NPU 映射 `"npu"`。

---

## 4. 本地 GGUF 模型加载（问题 2）

### 4.1 两条等价路径

**A. 直接路径喂引擎**：`LlmCreateInput.model_path` 接受任意应用可读路径的 `.gguf`；`tokenizer_path` 可空——**GGUF 自含 tokenizer 与模型配置，无需伴随文件**（`ModelPaths.tokenizer_path` 亦为可空字段，仅 qairt bundle 需清单管理）。【AAR+GH-main+DOC】

**B. LOCALFS 导入模型管理器（推荐，QAA 采用）**【DOC-models/supported】

```kotlin
// 冒烟模型释放到 filesDir 后，一次性导入 SDK 缓存（无网络）：
ModelManagerWrapper.pullFlow(ModelPullInput(
    model_name = "local/supra2.5-pro-instruct",
    hub        = HubSource.LOCALFS,
    local_path = smokeDirAbsoluteFile,  // filesDir 下含 *.gguf 的目录
)).collect { /* Progress / Completed / Error */ }

val paths = ModelManagerWrapper.getPaths("local/supra2.5-pro-instruct") ?: error("未导入")
LlmWrapper.builder().llmCreateInput(LlmCreateInput(
    model_path = paths.model_path, config = ModelConfig(nCtx = 4096),
    runtime_id = "llama_cpp", compute_unit = null,
)).build().getOrThrow()
```

- 目录内放 `geniex.json` 清单可被识别；**无清单则按文件名推断布局**；VLM 的 `mmproj-*.gguf` 放同目录即可。【DOC-models/supported】
- 导入即拷贝进 SDK 缓存，之后与网络下载的模型完全同构（getPaths/LlmWrapper 流程一致）。
- 存储布局：`filesDir/geniex/models/{org}/{repo}/...`，由 Rust 模型管理器独占管理（样例 App 专门清理了旧版 `filesDir/models`，反向印证）。【SAMPLE MyApplication.kt】

### 4.2 提示词契约（易踩坑）

`generateStreamFlow` 必须传 `applyChatTemplate(...).formattedText`（已模板化提示词），**传原始用户文本会产生退化输出**（两个 runtime 均如此，官方双重 WARNING）。【DOC-api-reference/quickstart】

---

## 5. 流式生成与取消（问题 3 前半）

### 5.1 Flow 形态【AAR javap ≡ GH-main】

```kotlin
fun generateStreamFlow(
    prompt: String? = null,         // 模板化后的完整提示词
    inputIds: IntArray? = null,     // 可选：token id 输入（与 inputEmbd 至多其一）
    inputEmbd: FloatArray? = null,  // 可选：嵌入输入
    inputEmbdDim: Int = 0,
    config: GenerationConfig,
): Flow<LlmStreamResult>

sealed class LlmStreamResult {
    data class Token(val text: String) : LlmStreamResult()
    data class Completed(val profile: ProfilingData) : LlmStreamResult()
    data class Error(val throwable: Throwable) : LlmStreamResult()
}
```

- 实现为 `callbackFlow` + `Dispatchers.IO`：native `generate` 阻塞在 IO 线程，token 经 `LLMTokenCallback.onToken(String): Boolean` 逐个 `trySend`；`onComplete(LlmGenerateResult)` → `Completed(profileData)` 后 `close()`；native 异常 → `Error(e)`。【GH-main LlmWrapper.kt】
- **0.10.0 已知毛刺（源码 FIXME/TODO 原文）**：①"result is null here when a callback is supplied"（带回调时 `generate` 返回值不可用）；②"TODO: handle the returned error code"（错误码以异常形态浮出，见 §9）。
- `LlmGenerateResult(fullText: String, profileData: ProfilingData)`：非流式调用（`jni.Llm.generate` 直用回调）时的完整文本载体。

### 5.2 取消方式（实现级）

1. `llmWrapper.stopStream()`（suspend）：llm_bridge_jni.cpp 维护 `g_stopFlags[handle]` 原子标志，回调循环检测后停止送 token。
2. `LLMTokenCallback.onToken` **返回 false**：jni_cb.cpp 将其作为 continue 标志回传 native（样例 Flow 内恒返回 true，实际靠 stopStream）。
3. **注意**：仅取消 Flow 采集（cancel）只会关闭 channel，**native generate 仍在 IO 线程阻塞运行**——QAA 的取消按钮必须调 `stopStream()`。
- 销毁：`close()/destroy(): Int`（释放 native 句柄并清零）；`reset(): Int` 清对话状态。【AAR】

### 5.3 采样与工具调用（与 Agent Loop 相关）

```kotlin
suspend fun applyChatTemplate(
    messages: Array<ChatMessage>, tools: String?, enableThinking: Boolean,
    addGenerationPrompt: Boolean = true, parallelToolCalls: Boolean = true,
): Result<LlmApplyChatTemplateOutput>   // formattedText + chatTemplateGrammar?
```

- `ChatMessage(role, content, toolCalls: List<ToolCall>, toolCallId, toolName, reasoningContent)`；`ToolCall(id, name, arguments)`——**SDK 原生支持工具调用模板渲染**。
- `ChatTemplateGrammar(grammarType, grammar, lazy, generationPrompt, triggers, preservedTokens)` + `GrammarTrigger`：模板应用时生成的惰性语法元数据，经 `SamplerConfig.chatTemplateGrammar` 回传即可约束模型按工具 JSON 语法出 token（QAA Agent Loop 的工具调用 JSON 解析可直接受益）。
- `SamplerConfig` 全 11 字段：`temperature / topP / topK / minP / repetitionPenalty / presencePenalty / frequencyPenalty / seed / grammarPath / grammarString / chatTemplateGrammar`；数值字段 null=用插件默认（temperature=0 即 greedy）。
- `GenerationConfig`（0.10.0 实际 10 字段，AAR 验证）：`maxTokens=32（务必按需调大）`、`stopWords`、`stopCount`、`samplerConfig`、`imagePaths/imageCount`、`audioPaths/audioCount`、`slidingWindow=false`、`slidingWindowNKeep=0`（后两者 qairt-only 环形上下文逐出，0=插件默认 4）。DOC 站表格尚缺 slidingWindow 两字段（文档滞后）。

---

## 6. TTFT / tok/s / token 计数（问题 3 后半）——SDK 直接提供

`Completed` 事件携带 **`ProfilingData`**，字段全表（AAR + 源码一致）：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `ttftMs` | Double | **首 token 延迟（ms）** |
| `mediaMs` | Double | 图/音编码耗时（纯文本为 0） |
| `promptTimeMs` | Double | prefill 耗时（含媒体 token，不含编码器） |
| `decodeTimeMs` | Double | 生成阶段耗时 |
| `promptTokens` | Long | 本次 prefill 实际评估的 prompt token |
| `totalPromptTokens` | Long | 请求的全量 prompt token（**含 KV 缓存复用部分**） |
| `generatedTokens` | Long | 生成 token 数 |
| `prefillSpeed` | Double | prefill tok/s |
| `decodingSpeed` | Double | **生成 tok/s** |
| `draftNTotal / draftNAccepted` | Long | 投机解码草稿/接受数（关闭为 0） |
| `stopReason` | String | "eos" / "length" / "user" / "stop_sequence" |

- 官方样例用法（MainActivity）：`streamResult.profile.ttftMs / promptTokens / prefillSpeed / generatedTokens / decodingSpeed` 直接拼状态栏文本展示。【SAMPLE】
- **结论：QAA `InferenceMetrics` 不需要自研测量**，每轮从 `Completed.profile` 直取即可。`TTFT` 由 SDK 语义化的 `ttftMs` 提供。
- 客户端兜底交叉校验（可选）：记录 `collect` 开始到首个 `Token` 事件的墙钟差 vs `ttftMs`，可校验调度层开销；若未来 SDK 移除某字段，此法可顶替。

---

## 7. n_ctx 与多轮会话（问题 4）

- **n_ctx 配置**：`ModelConfig.nCtx`（默认 2048；**0 = 用模型自带默认**）。`llama_cpp` 可自由调；`qairt` bundle 的 ctx/KV 编译期固化，`nCtx != 0` 直接 `PARAM_NOT_SUPPORTED`（§3.2）。
- **会话/历史管理**：SDK **不提供会话存储或 history API**——多轮 = 应用层维护消息数组，每轮 `applyChatTemplate(history)` 重建完整提示词再生成。这与 QAA 的 `TreeSessionStore(JSONL)` + 全量重建 prompt 模式天然契合。
- **KV 复用事实**：同一引擎句柄连续 `generate` 时，`ProfilingData.totalPromptTokens - promptTokens` 差值即 KV 缓存复用的 token 数（字段 KDoc 明示 "including those reused from the KV cache"）——多轮对话实际 prefill 成本远小于全量重算，`ContextCompactor` 的 `compactThresholdRatio=0.8` 预算可据此校准。
- `reset(): Int`：清空引擎对话状态；`nSeqMax=1`（单序列）；qairt 另有 `slidingWindow` 环形逐出（§5.3）。
- 模板定制：`ModelConfig.chat_template_path / chat_template_content` 可覆盖模型内置 chat template（QAA 极简 System Prompt 需求下通常不需要）。

---

## 8. 模型下载 API 与断点续传（问题 5）

### 8.1 pullFlow 全景【AAR javap ≡ GH-main ModelManagerWrapper.kt】

```kotlin
ModelManagerWrapper.pullFlow(input: ModelPullInput): Flow<PullEvent>

sealed class PullEvent {
    data class Progress(val files: List<FileProgress>) : PullEvent()
    object Completed : PullEvent()
    data class Error(val code: Int, val message: String) : PullEvent()
}
// FileProgress(file_name, downloaded_bytes, total_bytes /* -1=未知 */)
class GenieXModelError(val code: Int, message: String) : RuntimeException(...)
```

**ModelPullInput 全字段**：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `model_name` | 必填 | "org/repo"；本地导入用 "local/xxx" |
| `precision` | null | 精度提示（"Q4_0"/"Q4_K_M"…），null 由 SDK 选 |
| `hub` | AUTO(0) | AUTO 按 model_name 路由；HUGGINGFACE(1)/MODELSCOPE(2)/AIHUB(3)/VOLCES(4)/DOCKER(5)/LOCALFS(127) |
| `local_path` | null | 仅 LOCALFS 必填（目录或 .zip） |
| `hf_token` | null | HF token；null 回退 `GENIEX_HFTOKEN` 环境变量再匿名（**Android 无 env，应用内从安全存储传入**） |
| `chipset` | null | **Android 上 AIHUB 拉取必填**（"SM8750"/"SM8850"；Android 端无自动检测） |
| `display_name` | null | AIHUB 必填 |
| `model_type` | null | 写入清单的类型覆盖（LLM/VLM），null 自动判 |

### 8.2 断点续传（官方双重确认）

- 源码 KDoc：取消（Flow cancel 或进度回调返回 false）→ Rust 侧解当前分块并返回 `CANCELLED(-100006)`，**部分文件留在磁盘供下次续传**。
- Quickstart 原文："Downloads are resumable — killing the app mid-pull and re-running picks up where it left off."
- → QAA 的断点续传下载器**不需要自己实现 HTTP Range 续传**，复用 pullFlow + 进度落盘 UI 即可；`INSUFFICIENT_DISK_SPACE(-100017)` 等错误码见 §9。

### 8.3 管理面 API（jni.ModelManager / ModelManagerWrapper）【AAR】

`init(dataDir)` `deinit()` `lastErrorMessage()`｜`pull`｜`list(): String[]` `listDetailed(): ModelDetail[]` `getDetailed(name)`｜`getPaths(name): ModelPaths`｜`query(ModelPullInput): ModelQuery`｜`remove(name): Int` `clean(): Int`｜`getType/setType`｜`resolveAlias(name)`｜`listChipsets(): ChipsetInfo[]` `detectChipset(offline): String`｜`listHubModels(chipset): HubModel[]`

- `query()` 为**只规划不下载**：返回 `ModelQuery{model_name, runtime_id, model_type, candidates: PrecisionCandidate(precision, size)[]}` → **QAA `model pull` 命令应先 query 展示精度候选与体积，再选 precision 执行 pullFlow**。
- `listChipsets()`：AI Hub 支持芯片组（`ChipsetInfo(name, aliases)`）；`detectChipset()` 走 `ro.soc.model`（AIHUB 拉取仍需显式 chipset，§8.1）。
- HF token 环境变量口径：`HF_TOKEN` 与 `GENIEX_HFTOKEN`（后者优先）；gated 模型需先在 HF 接受协议。【DOC-models/supported + FAQ】

---

## 9. 错误码与降级信号（问题 6）

### 9.1 FFI 错误码全表（`sdk/model-manager/src/ffi/types.rs`）【JNI】

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `GENIEX_SUCCESS` | 0 | 成功 |
| `GENIEX_ERROR_COMMON_UNKNOWN` | -100000 | 未知 |
| `GENIEX_ERROR_COMMON_INVALID_INPUT` | -100001 | 非法输入 |
| `GENIEX_ERROR_COMMON_FILE_NOT_FOUND` | -100004 | 文件不存在 |
| `GENIEX_ERROR_COMMON_NETWORK` | -100005 | 网络 |
| `GENIEX_ERROR_COMMON_CANCELLED` | -100006 | 下载取消（部分文件保留可续传） |
| `GENIEX_ERROR_COMMON_NOT_INITIALIZED` | -100007 | 未初始化 |
| `GENIEX_ERROR_COMMON_ALREADY_INITIALIZED` | -100008 | 重复初始化（wrapper 视为成功） |
| `GENIEX_ERROR_COMMON_AUTH` | -100009 | 鉴权（HF token 等） |
| `GENIEX_ERROR_COMMON_HUB_MODEL_NOT_FOUND` | -100010 | hub 上找不到模型/精度 |
| `GENIEX_ERROR_COMMON_RATE_LIMITED` | -100011 | 429 |
| `GENIEX_ERROR_COMMON_HUB_SERVER` | -100012 | 5xx |
| `GENIEX_ERROR_COMMON_MANIFEST_PARSE` | -100014 | 清单解析失败 |
| `GENIEX_ERROR_COMMON_CHIPSET_UNAVAILABLE` | -100015 | 芯片组不支持 |
| `GENIEX_ERROR_COMMON_INSUFFICIENT_DISK_SPACE` | -100017 | 磁盘不足 |
| `GENIEX_ERROR_COMMON_MODEL_INVALID` | -100203 | 模型非法 |

（Rust 侧另有 MODEL_* 家族 -1002xx；HTTP 429→RATE_LIMITED、5xx→HUB_SERVER 的映射同文件可见。）

### 9.2 Kotlin 侧错误形态（按操作面）

| 操作 | 错误暴露方式 |
| --- | --- |
| `GenieXSdk.init` | `InitCallback.onFailure(reason: String)`——插件缺失/注册失败/`geniex_model_init failed (rc=...)` 逐项拼接 |
| `LlmWrapper.build`（native create） | JNI `throw_runtime_exception`（消息带 `geniex_get_error_message` 文案）→ `Result.failure`；std::exception 走 catch 返回 0 句柄 |
| `generateStreamFlow` | native 异常 → `LlmStreamResult.Error(throwable)`（错误码 TODO 现状见 §5.1） |
| `pullFlow` | `PullEvent.Error(code, message)`（`GenieXModelError` 同构）；CANCELLED 不发 Error |
| `ModelManagerWrapper.init` | `Result.failure(GenieXModelError(rc, "geniex_model_init failed"))` |
| qairt 参数越界 | `PARAM_NOT_SUPPORTED`（`nCtx!=0` / `nGpuLayers!=0`） |

### 9.3 降级信号口径

- **算子/精度不兼容在 GenieX 是"加载期落点选择"而非"运行期异常"**：Q4_0 → NPU、Q8_0/F16/K-quants → GPU/CPU（§3.3）；`hybrid` 模式下逐张量调度天然把 HTP 不支持的算子留在 CPU。
- **没有文档化的"运行中算子失败回调"**；若固定 npu 模式遇到不支持的量化/算子是报错还是自动回退，官方文档未载明——列 §13 真机待验证项（QAA 降级链的第一手验证点）。
- `resolve_device` 失败 → logcat `geniex_resolve_device failed (rc=%d), falling back to empty device` + 空 device（即 llama.cpp 默认调度）——这是一处**实现级降级路径**。

---

## 10. Android 集成要求（问题 7）

| 项 | 要求 | 来源 |
| --- | --- | --- |
| Gradle | `mavenCentral()` + `implementation("com.qualcomm.qti:geniex-android:0.10.0")`；无 NDK/CMake | 【DOC-install】 |
| minSdk | **27**（AAR manifest `minSdkVersion=27`；官方 install 页同口径） | 【AAR+DOC】 |
| ABI | 仅 arm64-v8a（模拟器 x86_64 不可跑推理，与 ARCHITECTURE"模拟器仅验 UI/逻辑"一致） | 【AAR】 |
| 应用 manifest | **必须自加 `INTERNET`**（AAR 无 uses-permission）；VLM 读相册另加 `READ_EXTERNAL_STORAGE` 或 13+ scoped media | 【AAR+DOC-install】 |
| 样例实践 | 另加 `WAKE_LOCK`（锁屏时下载保活）、`CAMERA`（VLM 拍照）；`extractNativeLibs="true"` + Gradle `jniLibs.useLegacyPackaging = true`（与 nativeLibraryDir 插件注册配套） | 【SAMPLE】 |
| 工具链 | 样例：AGP 8.13.0 / Kotlin 2.2.0 / Java 17 / compileSdk 34 / minSdk 31 | 【SAMPLE build.gradle】 |
| ProGuard | **AAR 未内置 consumer-rules（无 proguard.txt 条目）**。JNI `external fun` 依赖类名/方法名不变，release 混淆需应用侧 keep（建议 ```-keepclasseswithmembernames class com.geniex.sdk.** { native <methods>; }``` 等）；官方样例直接 `minifyEnabled false` 绕过——release 混淆实测列 §13 | 【AAR+SAMPLE】 |
| 日志 | tag `GenieXSdk` | 【GH-main】 |
| 官方验证 SoC（Android） | **Snapdragon 8 Elite（SM8750）与 8 Elite Gen 5（SM8850）**；"可能运行于同族其他骁龙部件"，AAR 亦携带 htp-v73~v85 库——**8 Gen 2（SM8550/v73）未列官方验证清单**，列 §13 真机待验证 | 【DOC-platforms+AAR】 |
| 无真机方案 | Qualcomm Developer Cloud（QDC）8 Elite / 8 Elite Gen 5 交互会话，上传样例 APK 实测 | 【DOC-faq】 |

模型侧附带要求：AI Hub（qairt）模型按芯片组预编译；GGUF 走 Hugging Face 无 chipset 要求。

---

## 11. 本机 QAIRT SDK 与 GenieX 的关系（问题 8）

### 11.1 名称与层次

- 官方名称口径："Qualcomm® AI Engine Direct（又称 Qualcomm AI Engine Direct SDK / Qualcomm AI Runtime / 历史 QAIRT）"【DOC-platforms】；本机 QAIRT 包 ReleaseNotes 亦自述 "Qualcomm AI Runtime SDK is also referred to as Qualcomm Neural Network (QNN)"。【QAIRT-本机】
- 层次：**GenieX（跨平台产品）→ 两个插件 → 底层引擎**：`llama_cpp` 插件 → llama.cpp + 自研 GGML Hexagon 后端（`libggml-hexagon.so` + `libggml-htp-vXX.so`）；`qairt` 插件 → AI Engine Direct（QNN，AAR 内整套 `libQnnHtp*` Skel/Stub/Prepare）。QAA 架构表"本机 QAIRT = GenieX qairt 路径底层"成立，但注意 **AAR 已内置一套 QNN 运行时，应用默认不依赖本机 QAIRT**。

### 11.2 setQairtRuntimePath：外挂 QAIRT 的官方通道【GH-main GenieXSdk.kt KDoc】

```kotlin
GenieXSdk.getInstance().setQairtRuntimePath(path)  // 必须在 init 之前调用
```

- `path` 可以是 **QAIRT SDK 根目录**或**平铺的 QNN 库目录**（应用可读路径）；`""` 恢复 AAR 内置运行时。
- 语义："运行在另一 QAIRT 版本上"；QNN 库每进程只加载一次且不可卸载，故 init 后调用会失败；路径不可用**在模型创建时**才报错。
- → QAA 若需让 qairt 路径对齐本机 `/home/thatgfsj/Downloads/v2.52.0.261104/qairt/2.52.0.261104`（adb push 到应用可读目录后 setQairtRuntimePath），需在进程首次 init 前完成；默认不推荐，先吃 AAR 内置版。

### 11.3 本机 QAIRT 2.52.0.261104 实测佐证【QAIRT-本机】

- `sdk.yaml`：`version: 2.52.0`、`qnn_backend_api_version: 2.18.0`。
- `lib/`：`hexagon-v66/68/69/73/75/79/81/85` + `aarch64-android` 等 → 架构表"hexagon v66~v85"证实；AAR 只随包 v73~v85（移动端子集）。
- 包内另有 **"Genie" C SDK 组件**（注意与 GenieX 产品名接近但不同物）：`include/Genie/` 15 个头文件（GenieEngine/GenieDialog/GenieSampler/GenieTokenizer/GeniePipeline/GenieEmbedding/GeniePerformance 等）、`examples/Genie/genie-t2t-run|genie-t2e-run`、`GENIE_README.txt`（自述：在 QNN 栈上跑生成式 transformer，仅限 LLM；支持 **QNN HTP 后端、QNN GPU 后端、以及 Snapdragon CPU（QNN GenAiTransformer 后端）**）、`docs/QAIRT-Docs/Genie/`（api-rst + index.html）。
- 关系结论：本机 QAIRT 里的 Genie 是面向 C/C++ 的 LLM 推理库（QNN HTP/GPU/CPU），GenieX 的 Kotlin API（LlmWrapper 等）不直接包装它；GenieX 的 qairt 插件走的是 AI Engine Direct 上下文。**QAA 不需要引用本机 QAIRT 的 Genie 头文件**；本机 SDK 的价值是 ①setQairtRuntimePath 换绑对象，②对照 QAIRT 版本行为（v66~v85 支持范围）。

---

## 12. 对 QAA 架构契约的落地映射建议

### 12.1 ComputeBackend → GenieX compute_unit

| QAA（ARCHITECTURE.md） | GenieX 建议 | 依据/风险 |
| --- | --- | --- |
| `AUTO` | `null`（=npu/HTP0）**或显式 `"hybrid"`（NPU+CPU 逐张量，官方称 LLM 快速路径）** | §3.1；hybrid 需显式传值，null 默认是 npu 不是 hybrid |
| `NPU` | `"npu"`（HTP0 固定） | §3.1 |
| `HYBRID`（QAA 注释=Adreno GPU） | `"gpu"`——**语义冲突点名：GenieX 的 hybrid ≠ GPU**，Lead 需定夺改名或改映射 | §3.5 |
| `CPU` | `"cpu"`（ngl 强制 0） | §3.1 |
| 降级链实现 | `BackendFallbackManager` 自管：create 级按 NPU→(GPU|HYBRID)→CPU 顺序重试 `Result.failure`；GenieX 无自动链 | §3.5/§9.3 |
| 后端状态流 | 锁定值（应用层）+ logcat `GenieXSdk` 解析（观测）+ `ProfilingData` 速度（推断）三源合一 | §3.4 |

### 12.2 冒烟模型接入（Supra2.5-Pro-Instruct Q4_0 GGUF，~40MB）

1. assets/首次启动释放到 `filesDir/smoke/`；
2. `pullFlow(LOCALFS)` 导入 → `getPaths` 缓存 `ModelPaths`（含权威 runtime_id="llama_cpp"）；
3. `ModelConfig(nCtx=2048)` + `compute_unit=null`（默认 npu）先行冒烟，再对比 `"hybrid"` 实测 tok/s（§6 指标直取）；
4. `applyChatTemplate` + `generateStreamFlow` 全流程与 §4/§5 示例一致；Q4_0 → NPU 落点由官方精度表背书（§3.3）。

### 12.3 其余模块挂钩

- **InferenceMetrics**：`Completed.profile`（ProfilingData）直映射；TTFT/tok/promptTokens/generatedTokens 全齐（§6）。
- **断点续传下载器**：直接复用 pullFlow；进度 UI 用 `FileProgress`（total_bytes=-1 需 UI 兜底"未知"）（§8）。
- **model pull 命令**（Terminal）：`query` 列精度候选 → 选定后 pullFlow → `getPaths` 入库；`list/listDetailed/remove/clean` 补全模型管理子命令（§8.3）。
- **ModelMemoryCoordinator**：LLM 卸载 = `stopStream()`（若在生成）→ `destroy()/close()`（native 句柄释放）；重载 = 重新 build；互斥状态机可全程在 Kotlin 层闭环，无需 SDK 特殊支持（§2/§5）。
- **Agent Loop 工具调用**：`ChatMessage.toolCalls` + `applyChatTemplate(tools=…)` + `ChatTemplateGrammar` 惰性语法，SDK 原生支持工具 JSON 约束生成（§5.3）。
- **多轮上下文**：全量重建 prompt + KV 复用（`totalPromptTokens` 佐证）；`ContextCompactor` 预算按 nCtx 校准；会话切换用 `reset()`（§7）。

---

## 13. 待验证清单（按优先级）

1. **8 Gen 2（SM8550 / htp-v73）实机**：官方验证列表只有 SM8750/SM8850；AAR 虽带 v73/v75 库，但 NPU 路径需真机确认（QAA 目标硬件下限）。
2. **固定 `npu` 模式遇不含量化/算子的行为**：报错 vs 自动回退 CPU——降级链设计的唯一未决事实，需 Q4_0 与 Q8_0 各跑一次 `create+generate` 实测。
3. **`compute_unit=null` 默认值**：device.cpp（main）= npu；0.10.0 二进制未逐指令验证，且 InputPluginBase KDoc（称 HYBRID）与之矛盾——以真机 logcat `[JNI] [extract] compute_unit` 为准。
4. **release 混淆**：AAR 无 consumer proguard 规则，JNI 方法名混淆后是否断链需 minify 构建实测（样例 App 用 minifyEnabled false 绕过）。
5. **logcat 观测格式稳定性**：后端解析日志为 native 实现细节，跨版本可能变动；QAA 状态栏不应唯一依赖它。
6. **样例 App 版本漂移**：其钉 0.3.5（含 `nPast` 等 0.10.0 已移除字段），借鉴代码需逐 API 对照本报告签名。
7. **VLM(GGUF) 的 NPU 支持**：样例注明 "VLM is not wired up for llama.cpp in the Android demo yet"；QAA 画图链路若走 VlmWrapper 需先真机验证。
8. **`setQairtRuntimePath` 换绑本机 QAIRT 2.52 的兼容性**：官方语义支持，但 0.10.0 内置 QNN 与 2.52 的组合未实测。
9. **token 计数口径**：`promptTokens/generatedTokens` 对中文与字节级 token 的计数语义需真机校准（InferenceMetrics 展示口径）。
10. **QAIRT Genie 组件与 GenieX 的正式关系声明**：本机 QAIRT 自带 Genie C SDK（HTP/GPU/CPU 三后端），官方文档未说明 GenieX 是否复用；当前仅可确认 GenieX qairt 插件走 AI Engine Direct（§11.3）。

---

## 14. 附录

### 14.1 关键 javap 签名（0.10.0 AAR 原文摘录）【AAR】

```text
public final class com.geniex.sdk.GenieXSdk {
  public static final java.lang.String PLUGIN_ID_QAIRT;
  public static final java.lang.String PLUGIN_ID_LLAMA_CPP;
  public final native int registerPlugin(java.lang.String);
  public final native java.lang.String getPluginVersion(java.lang.String);
  public final native java.lang.String getSdkVersion();
  public final native int setQairtRuntimePath(java.lang.String);
  public final native java.lang.String getQairtRuntimePath();
  public final void init(android.content.Context, com.geniex.sdk.GenieXSdk$InitCallback);
}
public final class com.geniex.sdk.jni.Llm {
  public final native long create(com.geniex.sdk.bean.LlmCreateInput);
  public final native int reset(long);
  public final native int destroy(long);
  public final native void stopStream(long);
  public final native com.geniex.sdk.bean.LlmApplyChatTemplateOutput applyChatTemplate(
      long, com.geniex.sdk.bean.ChatMessage[], java.lang.String, boolean, boolean, boolean);
  public final native com.geniex.sdk.bean.LlmGenerateResult generate(
      long, java.lang.String, int[], float[], int,
      com.geniex.sdk.bean.GenerationConfig, com.geniex.sdk.bean.LLMTokenCallback);
}
public final class com.geniex.sdk.jni.ModelManager {
  public final native int init(java.lang.String);
  public final native int deinit();
  public final native java.lang.String lastErrorMessage();
  public final native int pull(com.geniex.sdk.bean.ModelPullInput,
      com.geniex.sdk.callback.DownloadProgressCallback);
  public final native java.lang.String[] list();
  public final native com.geniex.sdk.bean.ModelDetail[] listDetailed();
  public final native com.geniex.sdk.bean.ModelDetail getDetailed(java.lang.String);
  public final native com.geniex.sdk.bean.ModelQuery query(com.geniex.sdk.bean.ModelPullInput);
  public final native com.geniex.sdk.bean.ModelPaths getPaths(java.lang.String);
  public final native int remove(java.lang.String);
  public final native int clean();
  public final native int getType(java.lang.String);
  public final native int setType(java.lang.String, int);
  public final native java.lang.String resolveAlias(java.lang.String);
  public final native com.geniex.sdk.bean.ChipsetInfo[] listChipsets();
  public final native java.lang.String detectChipset(boolean);
  public final native com.geniex.sdk.bean.HubModel[] listHubModels(java.lang.String);
}
public final class com.geniex.sdk.LlmWrapper implements java.io.Closeable {
  public final kotlinx.coroutines.flow.Flow<com.geniex.sdk.bean.LlmStreamResult>
      generateStreamFlow(java.lang.String, int[], float[], int, com.geniex.sdk.bean.GenerationConfig);
  public final java.lang.Object applyChatTemplate-hUnOzRk(...kotlin.coroutines.Continuation);
  public final java.lang.Object reset(kotlin.coroutines.Continuation<? super java.lang.Integer>);
  public final java.lang.Object stopStream-IoAF18A(...Continuation<? super kotlin.Result>);
  public void close();  public final int destroy();
  public static final com.geniex.sdk.LlmWrapper$Builder builder();
}
```

### 14.2 来源 URL 汇总

| 类别 | URL |
| --- | --- |
| Maven | https://repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0/geniex-android-0.10.0.aar ｜同目录 maven-metadata.xml / .pom |
| 文档站 | https://geniex.aihub.qualcomm.com/llms.txt ｜/en/run/android/install.md ｜/en/run/android/quickstart.md ｜/en/run/android/api-reference.md ｜/en/get-started/platforms.md ｜/en/get-started/what-is-geniex.md ｜/en/models/supported.md ｜/en/resources/faq.md |
| GitHub（SDK 源） | https://github.com/qualcomm/geniex ｜…/blob/main/docs/en/run/android/api-reference.mdx ｜https://github.com/qualcomm/GenieX/tree/main/bindings/android ｜…/bindings/android/README.md ｜…/bindings/android/app/src/main/java/com/geniex/sdk/（LlmWrapper.kt、GenieXSdk.kt、ModelManagerWrapper.kt、bean/*.kt）｜…/bindings/android/app/src/main/cpp/（jniutils.cpp、llm_bridge_jni.cpp、jni_cb.cpp）｜…/sdk/src/device.cpp ｜…/sdk/model-manager/src/ffi/types.rs ｜…/bindings/python/geniex/auto.py ｜…/bindings/go/device.go |
| 参考应用 | https://github.com/qualcomm/ai-hub-apps/tree/release/geniex_chat_android（钉 geniex-android:0.3.5） |
| 本机 QAIRT | /home/thatgfsj/Downloads/v2.52.0.261104/qairt/2.52.0.261104（sdk.yaml、include/Genie/、examples/Genie/、GENIE_README.txt、docs/QAIRT-Docs/Genie/） |

（完）本报告由 task-1 产出；引用了 AAR 反编译、官方文档、SDK 源码、JNI 实现与本地 QAIRT 实测五类来源，交叉验证后落笔。
