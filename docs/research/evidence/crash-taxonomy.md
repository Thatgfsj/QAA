# 崩溃分类 A/B/C 与触发条件（#97 取证口径）

> 全部内容摘自 `docs/benchmarks/phase2-report.md`「崩溃三类」段落与 #97 issue 正文/评论；**原始 `dumpsys dropbox` 文案未入库**（见 §5 缺口）。
> 本文件的作用是划清「崩溃」与「乱码」的边界：崩溃两类都不构成量化档位判据。

## 1. 判定总表

| 类 | 构建 | 后端 | 触发条件 | 现象/栈 | 是否与量化相关 | 处置 |
| --- | --- | --- | --- | --- | --- | --- |
| A | **debuggable**（`debuggable=true`，CheckJNI 生效） | NPU（`libnpu_jni.so`） | 任意**真实模型**输出非 ASCII 多字节 token（内置 67M ASCII 冒烟不触发） | `Llm_generate` → `NewStringUTF(非法 Modified-UTF-8)` → `art::CheckJNI::AbortF` → `abort` | 否（与量化无关，任何真实模型都可能） | 真机测试一律用非 debuggable 构建；不据此筛模型 |
| B | release（非 debuggable） | NPU/HTP | 长上下文回合（观测：冒烟 67M Q4_0 在 3647/4096 token 即触发；Spark-4B Q4_K_M 长上下文同型） | `llama_kv_cache::update` → `ggml_backend_sched_split_graph` → `ggml_abort` | 否（与量化、模型规模均无关） | 判为 HTP KV-cache 调度缺陷；NPU 长上下文规避/回退 CPU，不据此筛模型 |
| C | release（非 debuggable） | CPU | 无（长上下文、自动压缩、中文、80 s+ 长回合均稳定） | — | 否 | **本包全部乱码观测都在 C 类路径完成**，排除崩溃干扰 |

## 2. A 类：debuggable + Modified-UTF-8

- 机理：JNI `NewStringUTF` 遇到非法 Modified-UTF-8 字节序列（模型 token 含代理区间/截断的多字节序列），CheckJNI 直接 abort，绕过 Java 异常体系。
- 判别特征：`art::CheckJNI::AbortF`；内置 ASCII 冒烟模型**永不触发**，故不能用冒烟结论否定真模型问题。
- 对 #97 的意义：debug 包下真模型测试不可用 → 所有乱码/性能结论必须在 release 非 debuggable 包上取得（`docs/benchmarks/phase2-report.md` 已据此切换测试构建）。

## 3. B 类：HTP 路径 KV 调度 abort

- 机理：`llama_kv_cache::update` 期间 `ggml_backend_sched_split_graph` 计算图切分失败 → `ggml_abort`（native abort，无 Java 栈）。
- 关键判别：**不挑模型**——内置 67M Q4_0 冒烟模型在 NPU + 3647/4096 token 回合同样触发；CPU 路径同负载稳定。
- 结论：平台级（GenieX HTP KV cache 调度）缺陷，**不是**应用侧可修，也**不是**量化档位证据。
- 对 #102 的影响：无。门禁只筛「输出正确性」，不筛「NPU 长上下文可用性」；后者另开规避策略（限制 nCtx / NPU→CPU 回退）。

## 4. 术语纪律

| 说法 | 是否可用 |
| --- | --- |
| 「Q4_K_M 导致崩溃」 | ❌ 禁止：Q4_K_M 在 CPU 上只乱码不崩溃；崩溃由构建类型(A)或 HTP 长上下文(B)决定 |
| 「K 系量化导致乱码」 | ✅ 可用（有单变量对照） |
| 「Q8_0 已证明乱码」 | ⚠ 只能说「真机 1 例乱码，单变量未闭环」（Spark-1.7B Q8_0 与 spark2_5 架构混淆） |
| 「qwen35 架构不支持」 | ❌ 已被否定：qwen35 的 Q4_0 文件正常（Qwen3.5-4B-QUASAR-Q4_0 同 arch/pre） |

## 5. 缺口（待真机补）

1. `dumpsys dropbox --print data_app_native_crash` 原文（A/B 两类各一份，含 `Abort message:` 与 `#00 pc` 帧）。
2. `logcat -b crash` 连续 30 s 抓取，覆盖崩溃瞬间的 `GenieX`/`[JNI]` 行。
3. NPU 长上下文触发的最小复现步（消息序列 + `/status` 的 token 计数快照），用于向上游提 HTP 缺陷。