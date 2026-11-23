# QAA Pi-Style Harness 设计附录

> 隶属 docs/design/harness-core-design.md（v1.0）。附录 A 为 JVM 单测清单（供实现议题
> 直接引用验收）；附录 B 为扩展样例。上游契约仍以 docs/ARCHITECTURE.md 为准。

## A.0 测试基础设施约定

:core-harness 与 :core-tools 全部用例跑在 JVM（无 Android 依赖），统一依赖以下测试替身：

| 替身 | 用途 |
| --- | --- |
| FakeLlmEngine | 脚本化回复队列（按序弹出预设文本/metrics），可注入延迟与抛错 |
| InMemorySandboxFs / TempDirSandboxFs | 内存或 @TempDir 实现的 SandboxFs，可控路径越界用例 |
| FakeConfirmationGate | 脚本化 Allow/Deny 决策序列，可模拟挂起中 /steer 到达 |
| FakeMemoryProbe | 可编程内存水位读数，驱动 ModelMemoryCoordinator 校验点分支 |
| FakeImageGenEngine | load/generate/unload 可脚本化成败，驱动 9.5 接线用例 |

## A.1 AgentLoop

| # | 用例 | 断言要点 |
| --- | --- | --- |
| L1 | 纯文本回复 | 一轮即终；树仅 user+assistant 两节点；无 tool 节点 |
| L2 | 单工具闭环（read → 回注 → 终答） | step=1；assistant 节点 toolCalls 非空；tool 节点 SUCCESS；第二次生成无调用 |
| L3 | 多步链 read→edit→write | 顺序执行、步数累计正确、树时序正确 |
| L4 | maxToolSteps=12 硬顶 | 第 12 步后注入收尾提示；末次生成禁用工具解析；轮次必然终止 |
| L5 | Deny 路径 | tool 节点 DENIED；拒绝话术回注；步数照常累计 |
| L6 | /steer 工具步打断 | 当前工具完成即停；steer 文本成为新 user 节点（tags=steer） |
| L7 | 确认挂起中 /steer | 等价 Deny + 转向注入 |
| L8 | 解析降级 | 连续 2 次脏 JSON 后注入降级提示并禁用工具；轮次终止 |
| L9 | 终止不变量 | 对任意恶意脚本（循环调用、恒脏 JSON），runTurn 在 watchdog 超时内必返回 |

## A.2 AgentConfig

| # | 用例 | 断言要点 |
| --- | --- | --- |
| G1 | 默认值快照 | yoloMode=false / maxToolSteps=12 / compactThresholdRatio=0.8f（防契约漂移回归） |

## A.3 ToolCallParser

| # | 用例 | 断言要点 |
| --- | --- | --- |
| P1 | 围栏提取 | json 代码围栏与裸 JSON 均正确提取；多围栏取含 tool 的首个 |
| P2 | 前置闲聊 + 裸 JSON | 首花括号定位成功 |
| P3 | 字符串含花括号/转义引号 | 配平扫描不误截（含 "a{b\"c}" 类样例） |
| P4 | 截断修补 | 缺失引号/括号 ≤3 层可修补；超限失败走重试 |
| P5 | 顶层数组 | 按序展开为多个调用 |
| P6 | 未知工具名 | 触发纠错重试路径，不计工具步 |
| P7 | 非对象/空输入 | 判定失败，不抛异常 |
| P8 | 别名容忍 | name/arguments 兜底接受；canonical 优先 |
| P9 | 纯函数性 | 同输入恒同输出（可快照断言） |

## A.4 工具与 SandboxFs

| # | 用例 | 断言要点 |
| --- | --- | --- |
| T1 | read 截断 | 超 8000 字符输出带截断标记与总长 |
| T2 | read 二进制 | 前 8KB 含 NUL → E_BINARY_FILE |
| T3 | 路径逃逸 | ../ 逃逸、绝对路径、符号链接均 → E_PATH_ESCAPE |
| T4 | write 语义 | 不存在则建（含父目录）；已存在则全量覆盖 |
| T5 | write 原子性 | 注入写一半失败 → 旧内容完整保留（临时文件机制） |
| T6 | edit 唯一替换 | 恰好 1 处匹配成功且替换正确 |
| T7 | edit 多匹配 | replaceAll=false → E_MULTI_MATCH 并报匹配数；=true 全替换 |
| T8 | edit 未匹配 | E_NOT_FOUND + 最近似片段提示 |
| T9 | edit 空 newText | 等价删除匹配片段 |
| T10 | edit 空 oldText | E_EMPTY_OLD_TEXT |
| T11 | edit 隐式创建 | 目标不存在 → E_NOT_FOUND（不创建） |
| T12 | draw_image 占位 | Phase 2 引擎缺席 → E_ENGINE_NOT_AVAILABLE；不触发确认、状态机零迁移 |
| T13 | 确认常量 | read.requiresConfirmation=false；write/edit/draw_image=true |
| T14 | 参数校验 | 缺 path/类型错 → E_ARGS_INVALID（四个工具逐一） |

## A.5 TreeSessionStore

| # | 用例 | 断言要点 |
| --- | --- | --- |
| S1 | append 回读一致 | 节点字段 roundtrip 无损 |
| S2 | meta 后写覆盖 | activeLeafId 以最后一条 meta 为准 |
| S3 | /fork 缺参与带参 | 缺参=当前叶子；带参=指定节点；新节点 parentId 指向分叉点；原分支保留 |
| S4 | /fork 非法 id | 报错且树不变 |
| S5 | /fork 运行中拒绝 | Agent 轮次进行中返回提示，不写树 |
| S6 | 尾行半写容忍 | 最后一行损坏时加载跳过并告警 |
| S7 | 前向兼容 | 未知字段与更高 schemaVersion 的节点不致崩溃 |
| S8 | LoRA 导出 | 分支线性化正确；PENDING/解析失败剔除；tool_calls 映射；rejected 开关生效 |

## A.6 ContextCompactor

| # | 用例 | 断言要点 |
| --- | --- | --- |
| C1 | 阈值下不触发 | ratio<0.8 时安全点检查无副作用 |
| C2 | 阈值上自动触发 | ratio≥0.8 于工具步边界/轮末触发一次 |
| C3 | 保护窗口 | 当前轮全保留 + 上轮 user/assistant 文本 + 最新工具输出完整 |
| C4 | 范围引用 | summary.compaction 的 coveredFrom/coveredTo 沿 parentId 回溯语义正确 |
| C5 | 组装跳过 | 被压缩链段节点不出现在 prompt；summary 出现且时序正确 |
| C6 | /compact 手动 | 空闲立即；运行中排队至最近安全点 |
| C7 | 压缩水位 | 压后 usedRatio ≤ 0.5 |
| C8 | 估算兜底 | 无实测 metrics 时按 CJK/ASCII 近似且取 max(实测, 估算) |

## A.7 SteerQueue

| # | 用例 | 断言要点 |
| --- | --- | --- |
| Q1 | 优先级 | steer 恒先于 followUp 消费 |
| Q2 | 队内 FIFO | 同队列严格先来先服务 |
| Q3 | 流式中 steer | 纯文本生成中不硬切，轮末最高优先注入 |
| Q4 | 多条 steer | 逐条独立成轮、各自走完整循环 |
| Q5 | follow-up 衔接 | 轮次自然结束后逐条作为新 user 轮次 |

## A.8 SkillRegistry

| # | 用例 | 断言要点 |
| --- | --- | --- |
| K1 | 扫描注册 | 合规目录全部注册；描述段含 name+description |
| K2 | 校验失败跳过 | 缺 name/description/无 frontmatter → 告警跳过不崩溃 |
| K3 | 重名处理 | 后扫描者跳过并告警 |
| K4 | 稳定前缀不变量 | 描述段按 name 字典序；两次生成字节级相同（Prompt Cache 前提） |
| K5 | 命中读全文 | 引导语触发 read skills/&lt;name&gt;/SKILL.md；全文经 ToolResult 回注 |
| K6 | /skills 列表 | 与注入段同源数据 |

## A.9 ModelMemoryCoordinator

| # | 用例 | 断言要点 |
| --- | --- | --- |
| M1 | 合法全链 | LLM_LOADED→…→LLM_LOADED 七次迁移依序提交，各校验点按脚本通过 |
| M2 | 非法迁移拒绝 | 枚举全部非法边：状态不变 + IllegalTransitionException + E_MEMORY_MUTEX |
| M3 | 幂等重入 | 目标态==当前态直接成功 |
| M4 | 回收校验失败 | FakeMemoryProbe 报低水位 → 停留 LLM_UNLOADING 重试；耗尽走图外恢复路径 |
| M5 | 互斥不变量 | IMAGEGEN 态断言 LLM 引用=0；LLM 态断言引擎引用=0 |
| M6 | 串行 actor | 并发迁移请求按序裁决，无竞态提交 |
| M7 | 生成互拒 | 状态≠LLM_LOADED 时 streamGenerate 请求被拒绝 |

## A.10 PromptAssembler

| # | 用例 | 断言要点 |
| --- | --- | --- |
| PA1 | 组装顺序 | 系统段→skills 段→summary→保护窗口→当前轮 |
| PA2 | 稳定前缀 | 系统段+skills 段在会话内字节级不变 |
| PA3 | 收尾提示 | 步数预算耗尽提示只注入一次 |

## B.1 会话 JSONL 片段样例

实际文件一行一记录；下例为便于阅读的展开形式（sessionId=demo，schemaVersion=1）。

```json
{ "recordType": "node", "schemaVersion": 1, "id": "n-000002", "parentId": "n-000001",
  "role": "user", "content": "把 notes.txt 里的标题改成大写", "toolCalls": [],
  "tags": [], "timestamp": 1763932800123, "metrics": null, "compaction": null }

{ "recordType": "node", "schemaVersion": 1, "id": "n-000003", "parentId": "n-000002",
  "role": "assistant", "content": "我先读取该文件。",
  "toolCalls": [ { "callId": "c-000003-0", "tool": "read",
      "argsJson": "{ \"path\": \"notes.txt\" }" } ],
  "tags": [], "timestamp": 1763932800900,
  "metrics": { "backend": "NPU", "ttftMs": 210, "tokensPerSec": 18.4,
    "promptTokens": 1350, "completionTokens": 96, "stepIndex": 0,
    "parseRetries": 0, "durationMs": 4200 },
  "compaction": null }

{ "recordType": "node", "schemaVersion": 1, "id": "n-000004", "parentId": "n-000003",
  "role": "tool", "callId": "c-000003-0", "tool": "read",
  "content": "（文件内容，超限截断标记…）", "toolStatus": "SUCCESS", "errorCode": null,
  "tags": [], "timestamp": 1763932800980,
  "metrics": { "stepIndex": 0, "durationMs": 12 } }

{ "recordType": "node", "schemaVersion": 1, "id": "n-000009", "parentId": "n-000008",
  "role": "summary", "content": "用户目标：整理笔记。已完成：读取 notes.txt 并将标题改为大写。
关键路径：notes.txt。未决：无。",
  "toolCalls": [], "tags": [], "timestamp": 1763932900000, "metrics": null,
  "compaction": { "coveredFrom": "n-000002", "coveredTo": "n-000007",
    "nodeCount": 6, "estimatedTokens": 5200, "createdAt": 1763932900000 } }

{ "recordType": "meta", "schemaVersion": 1, "activeLeafId": "n-000009",
  "updatedAt": 1763932900000 }
```

## B.2 LoRA 微调轨迹导出样例行

一行一条样本（此处展开便于阅读）；系统段与运行时组装一致，保证训练/推理分布对齐。

```json
{
  "messages": [
    { "role": "system", "content": "你是端侧助手…（极简系统段）…可用技能列表…" },
    { "role": "user", "content": "把 notes.txt 里的标题改成大写" },
    { "role": "assistant", "content": "我先读取该文件。",
      "tool_calls": [ { "id": "c-000003-0",
        "function": { "name": "read", "arguments": "{ \"path\": \"notes.txt\" }" } } ] },
    { "role": "tool", "tool_call_id": "c-000003-0", "content": "（文件内容）" },
    { "role": "assistant", "content": "已将标题改为大写并写入原文件。" }
  ],
  "meta": { "branch": "main", "backend": "NPU", "promptTokens": 1350 }
}
```

## B.3 确认挂起展示字段清单（供两前端实现）

| 工具 | 必展示 | 可选展示 |
| --- | --- | --- |
| write | path、内容字节数、新文件/覆盖标记 | 变更前后内容片段 |
| edit | path、匹配数、oldText/newText 前后片段 | replaceAll 标记 |
| draw_image | prompt、缺省落盘目录提示 | 引擎占用预估提示（Phase 3） |
| read | （不挂起） | — |
