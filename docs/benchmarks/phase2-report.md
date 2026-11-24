# Phase 2 真机验收报告（#22）

- **日期**：2026-11-25 00:50 – 02:05（约 75 分钟连续实测）
- **设备**：小米 25102RKBEC（代號 myron）· SoC SM8850（骁龙 8 Elite Gen 5）· Android 17（SDK 37，HyperOS）· 15.4GB RAM · arm64-v8a
- **被测构建**：v0.1.3（main ef461e9）debug 与 release 双构建交替（release 用 debug keystore 自签，与 debug 同签名可保数据互切）
- **驱动方式**：adb + uiautomator 读屏 + 事件注入；中文拼音输入法会劫持注入文本，改用 ADBKeyboard 广播注入

## 环境注记（Android 17 / HyperOS 对 adb 的收紧）

1. `adb install -g` 被系统拒绝（INSTALL_GRANT_RUNTIME_PERMISSIONS 权限收紧），纯 `-r` 安装正常
2. `input tap/text` 注入需在手机上开「USB 调试（安全设置）」（小米版 INJECT_EVENTS 运行时闸；权限本身 shell 已持有）
3. 进程死亡后 MIUI 会自动弹出「语言与输入法」安全页，遮蔽被测应用，易误判为人为操作
4. 非 debuggable 构建不可 `run-as`，取证需在 debug 与 release 间切换（同签名保数据）

## 已验证链路（全部真机通过）

| 项 | 结果 |
| --- | --- |
| 安装 / 启动 / 双前端切换 | Normal → 设置 → 切换终端模式 → 重启生效 ✓ |
| 模型导入（SAF 全流程） | 3 次全通：Spark-X2.6-4B-Q4_K_M（3.3GB）/ Spark-X2.6-1.7B-Q8_0（1.7GB）/ Huihui-Qwen3.5-4B-abliterated（2.5GB），含小米选择器「浏览→下载→滚动→选中→确定」两段式驱动 |
| 终端命令面 | help 20 行（含 v0.1.3 新增 /logs）；↑ 长按弹层与清屏 ✓；键盘键可见（toggle 未在 ADBKeyboard 输入法下有意义验证） |
| 后端切换 | `backend cpu` / `backend npu` 锁定与引擎重载回执 ✓；命中侧诚实显示 [CPU]/[NPU] ✓ |
| 状态与日志 | /status 全字段（阶段/模型/请求后端/实际命中/统计/清单/会话/上下文预算）✓；/logs 两次导出，run-as 拉回正文核对：SDK 初始化、模型载卸、后端切换、导出事件齐全 ✓ |
| 会话机制 | 自动压缩自然触发 2 次（乱码长输出导致上下文膨胀，验证了压缩链路）；/export 回执含剔除统计（解析重试 5 节点 / 工具结果 5 条）；/fork 非法 ID 错误处理 ✓ |

## 性能实测

| 模型 | 量化 | 后端 | TTFT | 速度 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Supra2.5-Pro-Instruct（67M，内置） | Q4_0 | NPU | **25ms** | **409.6 tok/s** | 输出正常，基线 |
| Spark-X2.6-4B | Q4_K_M | CPU | 126–539ms | 111–134 tok/s | release 稳定；输出乱码（#97） |
| Spark-X2.6-1.7B | Q8_0 | NPU | 69ms | 162.6 tok/s | release 存活；输出乱码（#97） |
| Huihui-Qwen3.5-4B | Q4_K_M | CPU | 4583ms | 45.5 tok/s | 长上下文回合规程；输出乱码（#97） |

内存观察：应用侧 PSS 在载入 4B 后仅 340–360MB——GenieX 权重不驻应用地址空间（NPU 路径走 HTP 共享内存，dumpsys 不可见；CPU 路径疑似流式/映射加载）。真模型内存峰值需 DSP 侧工具另行取证。

## 崩溃根因取证（三类，dropbox 栈均留存）

### A · debuggable 构建 + 真模型回合：CheckJNI abort
栈：`libnpu_jni.so Llm_generate → NewStringUTF(非法 Modified-UTF-8) → art::CheckJNI::AbortF → abort`。
GenieX 0.10 的 JNI 生成回调对多字节 token 分片调用 `NewStringUTF`，字节流不满足 Modified-UTF-8 时 CheckJNI 直接终止进程。内置冒烟模型（ASCII 输出）不触发；一切真模型在 debug 包上表现为随机无声死亡。**结论：真模型测试一律使用非 debuggable 构建。**

### B · release + NPU + Spark-X2.6-4B(Q4_K_M)：ggml 调度器 abort
栈：`llama_kv_cache::update → ggml_backend_sched_split_graph → ggml_abort`。
GenieX 0.10 的 llama.cpp/NPU 后端无法为该量化图完成分割分配，原生自杀。为平台级限制，非应用代码问题。

### C · release + CPU：稳定
4B 长回合（80 秒+含流式）无死亡；多轮模型切换 / 导入 / 导出连续操作无异常。

## 模型兼容性发现（#97）

三个不同家族的模型文件在 NPU 与 CPU 双路径一致输出乱码汤，而内置 67M 模型输出正常——指向 GenieX 0.10 对非目标架构（词表/模板）的兼容缺口，而非单文件损坏。待 llama 架构标准模型交叉验证。

## 缺陷与后续议题

- #97 GenieX 模型兼容矩阵与乱码根因（含 CheckJNI 与 NPU 调度 abort 两项取证）
- #98 模型清单影子条目：内置模型冷启动重复登记（实测累积至 3 条）
- #99 /tree 无输出（树数据存在但渲染缺失）
- #100 /compact 回执疑似压缩后 token 反而变大

## 验收结论

#22 原定目标逐项：

1. 冒烟模型基线（NPU TTFT/tok/s）——**达成**
2. 3B/4B 级真模型上机与性能实测——**通道达成**（USB 推送 + SAF 导入三连全通；`model pull` 网络通道未测），性能已采样；4B-NPU 组合被平台级 abort 阻断（#97）
3. 全链路 Agent 闭环——**机制链路验证达成**：树/压缩/导出/日志全通；工具确认门依赖模型产出合规工具调用，三模型均乱码未及触发（解析器在乱码中仍捕获 5 次可解析片段并留下工具结果记录，链路可达）
4. 归档与关单——**本报告即归档**，`Closes #22`

总体：端到端链路在 8 Elite Gen 5 真机全部打通；v0.1.3 的新特性（长按次选、/logs）真机通过；两类原生崩溃完成根因取证并转 #97；真模型智能质量受 GenieX 兼容性制约，为 Phase 2 唯一实质阻塞项。

## 复现备忘

- 驱动链：`svc`/`input` 受限 → 开「USB 调试（安全设置）」→ `ime set com.android.adbkeyboard/.AdbIME` → 文本经 `am broadcast -a ADB_INPUT_TEXT --es msg '...'` 注入（带空格需整条远端引号）→ `input tap/swipe(900ms 长按)` 点按
- 读屏：`uiautomator dump` 文本节点抽取；截图仅存档（模型不可读图）
- 取证：debug 包 `run-as` 拉导出文件；崩溃栈 `dumpsys dropbox --print data_app_native_crash`（注意列表按时间序，取尾为最新）
