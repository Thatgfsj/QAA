# 更新日志

本文件记录面向用户的版本级变更；单次合入的明细见对应 Issue 与 Pull Request。

## v0.1.5（打磨五 · 发布）

- 文档：GenieX 0.10 量化兼容性取证包（#97）——Q4_0 可用 / K 系全乱码的成对对照证据、乱码原文、GGUF 头与张量直方图、崩溃分类，并给出 #102 门禁「按张量角色判定」的实测依据
- XML 方言解析边界收敛（#108）：整块实体转义形态（`&lt;tool_call&gt;`）可反解提取，参数值五类实体还原；闭合标签容忍标签内空白；嵌套标签/缺 `<function=` 等畸形转 Invalid 纠错而非静默终答；重复参数键后者覆盖；Qwen 多行参数保留缩进（组合调用仍由 JSON 管线兜底，围栏与示例语境不执行）
- 系统提示词可编辑（#105）：终端 `/system`（无参查看全文与来源、`/system <文本>` 整体替换、`/system reset` 恢复默认）与 Normal 设置页多行编辑双入口，共用 `system_prompt` 持久化，保存后下一轮生效、无需重载引擎；内容未变时稳定前缀字节级一致（保护 NPU Prompt Cache）；`/export` 导出当前提示词快照并标注来源（默认/自定义）

## v0.1.4（打磨四 · 已并入 v0.1.5）

- Agent 工具调用解析兼容模型自带的 XML 方言（`<tool_call>…<arg_key>…<arg_value>…`、Qwen `<function=…><parameter=…>`），修复真机上「模型已正确产出工具调用、却被当纯文本回显、确认门不触发」的问题；「含工具痕迹但解析失败」改判为纠错重试，不再静默吞调用（#107）
- 上下文长度可配置：默认 32768（档位 4096 / 8192 / 16384 / 32768），终端 `/ctx` 与 Normal 设置页双入口、DataStore 持久化；预算改为 nCtx − 输出预留 − 安全边界派生（32768→31600），压缩回执与 `/status` 同口径；生成前预检拦截超限；载入失败自动回退 4096；≤8192 即时重载、更大档位在重新载入模型后生效（#106）

## v0.1.3（打磨三）

- 终端快捷键长按次选：↑=清屏、↓=最早历史、yes=开启本会话自动放行、no=关闭自动放行（恢复逐次询问）；新增「键盘」键收起/展开软键盘（#93）
- `/logs [行数]`：应用内运行日志导出到沙盒（环形缓冲 500 行、默认 200 行；覆盖 SDK 初始化、模型载入、后端切换、确认与工具执行、错误码），真机排障无需 ADB（#94）

## v0.1.2（打磨二）

- 模型切换载入进度提示：终端「载入中」占位行 + Normal 载入中态（#88）
- 内置技能随 APK 释放：workspace-browse / careful-edit，首启 /skills 即见（#89）
- `/export` 导出会话轨迹：默认 exports/session-<id>.jsonl，回显行数与剔除统计（#90）
- Normal 会话页轻量指标条：TTFT / 速度 / Token，未载入显示占位（#91）

## v0.1.1（体验打磨）

真机首用反馈修正（#78–#83）：

- 顶栏与底部适配手机圆角/安全区（safeDrawing：状态栏/刘海/手势条不再遮挡内容）
- 终端快捷键真实化：↑/↓ 输入历史翻阅、yes/no 一键 Allow/Deny（无挂起时中文引导）
- `chat` 进入会话清屏净化：零系统回显、纯空态直达输入
- 内置冒烟模型固定 0 号基准，下载/导入模型自 1 起
- Normal 模型管理页新增下载入口：URL 一键断点续传，退出 App 状态保留、重进可继续
- 文案统一："极客终端模式"更名为"终端模式"

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
- Normal 窗口（会话/模型管理/设置）与 Terminal 终端模式（三段式布局 + 实时统计栏）
- ui_mode 持久化路由，切换即重启生效（设置页 / start window）
- Terminal 全中文命令集：help / model / chat / agent / backend / clear / start window / /exit / /status / /tree / /fork / /compact / /steer / /skills / /new
- backend 锁定即时生效（已加载时自动卸载重载，失败回滚旧后端）；/new 新建会话（自增 id、旧会话文件保留）

### 质量基线
- 全仓 700+ JVM 单测零失败；六大模块依赖边界经独立评审与代码索引复查
- release 构建验证通过（当前不启用混淆，规则文件含 GenieX JNI keep 预案）
