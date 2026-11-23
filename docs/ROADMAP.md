# 交付路线图与验收标准

> 议题即验收：每个 Phase 的 In-Scope 项均对应仓库 Issue，完成以 Issue 闭环为准。

> **2026-11-24 用户指令调整**：
> 1. 仓库转公开，许可证由 Apache-2.0 变更为 GPL-3.0-or-later。
> 2. **真机验收（#22）与端侧画图（#23 / #24）暂缓实施**，待用户明确指令后重启；其余议题照常推进。
> 3. Phase 2 的 Agent 工具闭环在暂缓期内先以模拟器 + 冒烟模型跑通逻辑链路；3B/4B 模型的 Tool Calling 质量验收随 #22 真机重启后执行。

## Phase 1 · 工程骨架、双前端路由与 GenieX 冒烟

- [ ] Android Kotlin 工程骨架：六模块、minSdk 27、arm64-v8a、Compose、版本目录
- [ ] 引入 `com.qualcomm.qti:geniex-android:0.10.0` 并验证依赖解析与原生库打包
- [ ] 双前端持久化切换（ui_mode）与立即重启路由
- [ ] 内置 Supra2.5-Pro-Instruct (Q4_0) GGUF 到 assets/，首启自动释放，零网络依赖
- [ ] GenieX 推理跑通 NPU → Hybrid → CPU 自动降级链与实际后端回显
- [ ] 终端 Shell 基础命令：help、model、chat、backend、clear、start window、/exit、/status
- [ ] 三段式对话布局与统计栏：TTFT、tok/s、已用/总 Token

**验收**：模拟器安装即运行（断网首启 chat 流式回复）、双前端切换重启正确、命令与统计栏可用、后端状态回显。

## Phase 2 · Pi-Style Harness 内核、本地沙盒与真机验证

- [ ] 纯 Kotlin Agent Loop；非 YOLO 确认流（Allow / Deny）+ `yoloMode` 预留接口
- [ ] 严格 Tool JSON 解析与容错（防脏 JSON 循环崩溃）
- [ ] 树状 JSONL 会话存储（/tree、/fork，可导出 LoRA 微调轨迹）
- [ ] 自动 + 手动 Compaction（/compact，阈值 0.8）
- [ ] 运行中干预队列（/steer 打断 + Follow-up 衔接）
- [ ] SKILL.md 渐进式加载（/skills）
- [ ] App 私有目录 + SAF 外部授权目录（storage grant）的 read / write / edit 工具
- [ ] model pull 断点续传 + model import SAF 旁加载
- [ ] 核心模块单元测试套件（JVM 可跑）
- [ ] ~~ADB 骁龙真机：3B/4B Q4_0 实测 NPU 推理速度 + 完整 Agent 工具闭环~~（**暂缓**，#22）

**验收**：Agent 全流程（skills 命中 → read → 确认 → write → fork → compact → steer）在模拟器 + 冒烟链路上逻辑可回溯；真机 NPU 实测（TTFT、tok/s、内存峰值）暂缓至 #22 重启后记录入 docs/benchmarks/。

## Phase 3 · 端侧画图与内存互斥调度（人工门禁 · **已暂缓**）

- [ ] ImageGenEngine 底座选型决策（**需用户拍板后实施**）
- [ ] 接入 draw_image 工具
- [ ] 跑通"LLM 写词 → 用户确认 → 卸载 LLM → 出图 → 卸载画图 → 按需重载 LLM"互斥状态机，全程无 OOM

**验收**：真机全链路无 OOM，状态机各阶段在统计栏可见，实测记录入 docs/benchmarks/。

## 负面约束（全阶段有效）

- 不添加语音交互模块（ASR / TTS）
- 不默认开启 yoloMode
- 不允许 LLM 与画图模型同时驻留内存
- 不引入与核心目标无关的重型框架
- 未经用户确认不跳步实现 Phase 3 画图引擎
