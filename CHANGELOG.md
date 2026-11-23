# 更新日志

本文件记录面向用户的版本级变更；单次合入的明细见对应 Issue 与 Pull Request。

## v0.1.0（发布候选）

首个功能完备版本：端到端 Agent 循环全链路可用（冒烟模型内置，安装即运行）。

### 端侧推理（:core-inference）
- GenieX Android SDK 0.10.0 接入（Maven Central，自带 arm64-v8a 原生库）
- 后端降级链自实现：NPU → HYBRID（官方 HTP+CPU 快速路径）→ GPU（Adreno 链内附加档）→ CPU，命中面 ActiveBackend 如实回显
- InferenceMetrics：TTFT / tok/s / Token 计数直取 GenieX ProfilingData
- 内置冒烟模型 Supra2.5-Pro-Instruct Q4_0（43.5MB）首启幂等释放，零网络依赖
- model pull 断点续传（HuggingFace / ModelScope）与 model import SAF 旁加载

### Pi-Style Harness（:core-harness）
- 极简 Agent Loop：maxToolSteps=12 + 解析重试双封顶，构造性无死循环
- 工具调用五级容错解析（围栏/定位/配平/截断修补/字段校验）+ 数组多调用
- 树状 JSONL 会话：/tree /fork 分叉、LoRA 微调轨迹导出
- 自动 + 手动上下文压缩（/compact，阈值 0.8，保护窗口，水位校验）
- 运行中干预：/steer 打断（含确认挂起中等价 Deny+转向）与 Follow-up 队列
- SKILL.md 渐进式加载（稳定描述段保护 Prompt Cache）
- token 级流式回显（AgentLoop onToken 全链路透传）

### 沙盒与工具（:core-tools）
- read / write / edit / draw_image 四原语；write / edit / draw_image 默认需用户 Allow/Deny 确认（非 YOLO）
- 多授权根沙盒：App 私有目录 + SAF 外部授权根，词法 + 真实路径双校验防逃逸
- ModelMemoryCoordinator 六状态互斥状态机：严禁 LLM 与画图模型同时驻留内存

### 双前端（:app + :feature-*）
- Normal 窗口（会话/模型管理/设置）与 Terminal 极客终端（三段式布局 + 实时统计栏）
- ui_mode 持久化路由，切换即重启生效（设置页 / start window）
- Terminal 全中文命令集：help / model / chat / agent / backend / clear / start window / /exit / /status / /tree / /fork / /compact / /steer / /skills

### 质量基线
- 全仓 700+ JVM 单测零失败；六大模块依赖边界经独立评审与代码索引复查
- release 构建验证通过（当前不启用混淆，规则文件含 GenieX JNI keep 预案）
