# QAA 架构说明

> 单一事实来源：版本、接口契约与硬约束以本文为准；修改需通过 Issue → PR。

## 1. 技术事实表（钉死，勿漂移）

| 项 | 值 | 备注 |
| --- | --- | --- |
| applicationId | `com.thatgfsj.qaa` | 可在拍板前统一调整，调整需同步本文 |
| 端侧推理 | `com.qualcomm.qti:geniex-android:0.10.0`（Maven Central） | 附带 arm64-v8a 原生库，应用侧无需 NDK/CMake |
| minSdk / ABI | 27 / 仅 `arm64-v8a` | 官方要求 minSdk 27 |
| 语言与 UI | Kotlin 2.x + Jetpack Compose + Coroutines，Java 17 toolchain | |
| 推理路径 | GenieX llama_cpp（GGUF），量化优先 Q4_0 | Hexagon NPU 对 Q4_0 算子支持最完整 |
| 冒烟模型 | Supra2.5-Pro-Instruct **Q4_0 GGUF**（67M，约 40MB） | HuggingFace：liodon-ai/Supra2.5-Pro-Instruct-GGUF |
| 目标硬件 | 骁龙 8 Gen 2 ~ 8 Elite Gen 5 | 模拟器仅验 UI/逻辑 |
| 参考实现 | github.com/qualcomm/ai-hub-apps → `geniex_chat_android` | 官方参考 App |
| 本机 QAIRT SDK | `/home/thatgfsj/Downloads/v2.52.0.261104`（hexagon v66~v85） | GenieX qairt 路径底层 |

## 2. 模块依赖

```mermaid
graph TD
    APP[:app 入口与路由] --> NUI[:feature-normal-ui]
    APP --> TUI[:feature-terminal-ui]
    NUI --> HAR[:core-harness]
    TUI --> HAR
    HAR -. 逻辑契约 HarnessPorts .-> INF[:core-inference]
    HAR -. 逻辑契约 HarnessPorts .-> TOOL[:core-tools]
    TOOL --> INF
    INF --> GENIEX[GenieX SDK 0.10.0]
```

> 依赖注记：:core-harness 为纯 JVM 库，图中 HAR→INF / HAR→TOOL 为**逻辑契约**关系（虚线）——端口接口定义在 :core-harness（HarnessPorts.kt），由 :app 在装配层注入 Android 侧实现（依赖倒置）；:app 同时承担 harness 与 inference/tools 的运行时装配。

| 模块 | 职责 | 禁止 |
| --- | --- | --- |
| :app | ui_mode 持久化路由、双前端启动、立即重启工具、DI 装配 | 业务逻辑 |
| :core-inference | LlmEngine、BackendFallbackManager、InferenceMetrics、内置模型释放器、断点续传下载器、ModelMemoryCoordinator | 引入 Compose / Android UI |
| :core-harness | Agent Loop、TreeSessionStore(JSONL)、ContextCompactor、SteerQueue、SkillRegistry、AgentConfig | 引入 Android 框架依赖（必须可 JVM 单测） |
| :core-tools | ToolExecutor 统一路由、沙盒文件系统、read/write/edit、ImageGenEngine 抽象、draw_image 占位 | 直接持有 GenieX 对象 |
| :feature-normal-ui | 会话页 / 模型管理页 / 设置页 / 后端状态栏 | 绕过 harness 直连引擎 |
| :feature-terminal-ui | Shell 命令解析器、全中文 help、三段式视图、实时统计栏 | 同上 |

## 3. 核心契约接口（保持同等解耦粒度）

```kotlin
enum class ComputeBackend { AUTO, NPU, HYBRID, CPU }

data class AgentConfig(
    val yoloMode: Boolean = false,          // 手机端默认关闭，预留扩展接口
    val maxToolSteps: Int = 12,
    val compactThresholdRatio: Float = 0.8f,
)

interface ToolExecutor {
    val name: String
    val requiresConfirmation: Boolean
    suspend fun execute(argsJson: String, context: ToolContext): ToolResult
}

interface ImageGenEngine {
    suspend fun load(modelPath: String)
    suspend fun generate(prompt: String, outputPath: String): Result<String>
    suspend fun unload()
}
```

## 4. 后端降级链（GenieX 事实对照）

GenieX SDK 无内建自动降级链与实际后端查询 API（事实依据：docs/research/geniex-api.md）；降级链由 QAA `BackendFallbackManager` 在引擎 create 级别自实现：任一档 `Result.failure` 即尝试下一档，顺序 **NPU → HYBRID → GPU（附加尝试）→ CPU**。

### 4.1 ComputeBackend 与 GenieX compute_unit 映射（2026-11-24 定夺）

| QAA ComputeBackend | GenieX compute_unit | 语义 |
| --- | --- | --- |
| AUTO | 依降级链依次显式尝试 | npu → hybrid → gpu → cpu |
| NPU | `"npu"` | Hexagon HTP0 固定（Q4_0 官方精度表背书） |
| HYBRID | `"hybrid"` | HTP + CPU 逐张量混合调度（GenieX 官方 LLM 快速路径） |
| （链内附加档，不暴露命令） | `"gpu"` | Adreno OpenCL；仅 HYBRID 失败后尝试 |
| CPU | `"cpu"` | 纯 CPU（ngl=0） |

> 语义修正：HYBRID 指官方 HTP+CPU 混合快速路径（早期注释"Adreno GPU"作废）；Adreno 保留为降级链内附加尝试档。`backend [auto|npu|hybrid|cpu]` 命令集保持不变。

### 4.2 实际命中后端感知与工程约束

- 三源合一感知：显式锁定值（应用层记录）+ logcat（tag `GenieXSdk`）+ `ProfilingData` 速度侧写（prefillSpeed / decodingSpeed 数量级差异）
- `ProfilingData` 直供指标：ttftMs / promptTokens / generatedTokens / prefillSpeed / decodingSpeed / stopReason，`InferenceMetrics` 直接映射
- 流式取消必须调用 `stopStream()`（仅取消 Flow 不停止 native 生成）
- release 混淆需应用侧 keep GenieX JNI 方法（AAR 无 consumer proguard 规则）
- Normal 状态栏与 Terminal 顶部常驻栏订阅同一后端状态流

## 5. ModelMemoryCoordinator 互斥状态机（硬约束）

手机 RAM 与 NPU 上下文有限，**严禁 LLM 与画图模型同时驻留内存**：

```mermaid
stateDiagram-v2
    [*] --> LLM_LOADED
    LLM_LOADED --> LLM_UNLOADING: draw_image 触发 + 用户 Allow
    LLM_UNLOADING --> IMAGEGEN_LOADING: 卸载完成 + 内存回收校验通过
    IMAGEGEN_LOADING --> IMAGEGEN_RUNNING: 加载完成
    IMAGEGEN_RUNNING --> IMAGEGEN_UNLOADING: 出图落盘至沙盒
    IMAGEGEN_UNLOADING --> LLM_RELOADING: 释放校验通过
    LLM_RELOADING --> LLM_LOADED: 回注图片路径与摘要
    LLM_LOADED --> [*]: 会话结束
```

强制流转：LLM 产出提示词 → draw_image → Allow 确认（非 YOLO）→ 卸载 LLM（校验回收）→ 加载 ImageGenEngine 出图落盘 → 卸载 → 按需重载 LLM → 结果回注 Agent Loop。任何非法状态迁移必须被状态机拒绝并报错。

## 6. 双前端启动路由

- `ui_mode`（NORMAL / TERMINAL）经 DataStore 持久化，MainActivity 启动时读取并路由至独立入口，两视图状态互不共享。
- Normal → Terminal：设置页"切换至极客终端模式" → 写配置 → 立即重启 App。
- Terminal → Normal：终端输入 `start window` → 写配置 → 立即重启 App。

## 7. Agent Loop 概览

单步循环：模型流式输出 → 工具调用 JSON 严格解析（容错提取 + 重试上限）→ 非授权确认（Allow / Deny，yoloMode 预留默认 false）→ ToolExecutor 执行 → 结果回注 → 直至无工具调用或达 maxToolSteps。工具仅 4 个原语：`read` `write` `edit` `draw_image`；System Prompt 保持极简以降低 Prefill 开销。
