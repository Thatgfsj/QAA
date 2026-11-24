# QAA · Qualcomm Agent App

![minSdk](https://img.shields.io/badge/minSdk-27-blue) ![abi](https://img.shields.io/badge/ABI-arm64--v8a-orange) ![GenieX](https://img.shields.io/badge/Qualcomm%20GenieX-0.10.0-3253DC) ![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF) ![License](https://img.shields.io/badge/License-GPL--3.0-blue)

运行在 **高通骁龙 8 Gen 2 ~ 8 Elite Gen 5** 手机上的原生 Android 智能体 App：端侧本地运行大语言模型（LLM）与画图模型，采用 **Pi 风格极简 Agent Harness**，实现可控、低延迟、可回溯的端侧智能体工作流，并为 PC 远控与端侧模型微调预留标准扩展接口。

## 核心特性

| 能力 | 说明 |
| --- | --- |
| 端侧推理底座 | Qualcomm GenieX Android SDK（Maven Central），llama_cpp（GGUF）路径，Q4_0 优先 |
| 后端降级链 | Hexagon NPU → HYBRID（HTP+CPU）→ GPU（Adreno 附加档）→ CPU，自动回退并在状态栏回显实际命中后端 |
| Pi-Style Harness | 纯 Kotlin 极简 Agent Loop，最小工具原语 `read / write / edit / draw_image`，与 UI 彻底解耦 |
| 双前端 | Normal 正常窗口 / Terminal 终端模式三段式布局，持久化 `ui_mode` 路由，切换即重启生效 |
| 树状会话 | JSONL 树形节点存储，`/tree` `/fork` 任意节点分叉，可直接导出为 LoRA 微调轨迹 |
| 渐进 Skills | 启动仅注入 SKILL.md 名称+一句话描述，命中任务才读全文，保护端侧 Prompt Cache |
| 内存互斥 | ModelMemoryCoordinator 状态机强管控，严禁 LLM 与画图模型同时驻留内存 |
| 内置冒烟模型 | Supra2.5-Pro-Instruct（Q4_0 GGUF，67M 参数）打包进 APK，安装即运行、零网络 |

## 硬件与边界

- **目标硬件**：骁龙 8 Gen 2 ~ 8 Elite Gen 5（arm64-v8a，minSdk = 27），优先 Hexagon NPU，兼容 Adreno GPU 与 CPU 兜底。
- **内置冒烟模型**：[liodon-ai/Supra2.5-Pro-Instruct-GGUF](https://huggingface.co/liodon-ai/Supra2.5-Pro-Instruct-GGUF) 的 Q4_0（67M 参数，约 40MB）。定位明确：**仅用于开箱即用、Phase 1 推理链路冒烟测试与保底回退**，不承担 Phase 2 Tool Calling 与上下文压缩验收。
- **分级测试策略**：本机模拟器仅验证双前端 UI、重启路由、命令解析、文件沙盒与基础逻辑；3B/4B 级 Q4_0 模型的 NPU 性能实测（TTFT、tok/s、内存峰值）与完整 Agent 闭环走 ADB 连接的骁龙真机。

## 模块结构

```
:app                  应用入口、ui_mode 持久化路由、重启工具、依赖装配
:core-inference       GenieX 封装、后端降级链、内置模型释放器、断点续传、ModelMemoryCoordinator
:core-harness         Pi-Style Agent Loop、树状 JSONL 会话、Compaction、SteerQueue、SkillRegistry
:core-tools           ToolExecutor 统一接口、沙盒文件系统、read/write/edit、ImageGenEngine 抽象
:feature-normal-ui    正常窗口前端：会话 / 模型管理 / 设置
:feature-terminal-ui  终端模式：Shell 命令解析、三段式会话视图、实时统计栏
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 快速开始

```bash
git clone https://github.com/Thatgfsj/QAA.git && cd QAA
# 冒烟模型注入脚本随 #5 落地：下载 Supra2.5-Pro-Instruct-Q4_0.gguf 至 app assets
./scripts/fetch-smoke-model.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Normal 模式：设置页 → "切换至终端模式" → App 立即重启进入终端。
- Terminal 模式：输入 `start window` → 立即重启回正常窗口；`help` 查看全中文命令说明。

## 更新日志

版本级变更见 [CHANGELOG.md](CHANGELOG.md)。

## 工程规范

- 一切云端修改走 Issue → 分支 → PR；单文件 ≤1000 行；单 PR 尽量 ≤1000 行。
- 不在每次 PR 运行 CI，构建阶段统一运行构建与测试。
- 面向用户文案一律使用清晰准确的中文。

## 许可证

[GPL-3.0-or-later](LICENSE)
