# 工程协作规范

## 云端变更流程

1. 一切云端仓库修改必须先建 Issue，再通过分支 + Pull Request 完成，PR 描述需含 `Closes #N`。
2. 不在每次 PR 时运行 CI / 构建；统一在构建阶段由 Lead 运行 `./gradlew test :app:assembleDebug` 做黑盒验收。
3. 同一问题产生过多 PR 导致冲突风险时，合并为单个 PR 统一处理（需用户确认）。
4. 分支命名：`feat/<议题>`、`docs/<主题>`、`fix/<缺陷>`；合并后删除远端与本地分支。
5. 构建产物（.apk、build/、.gguf）不入库；冒烟模型经脚本在构建前注入 assets。

## 硬性行规

- **单文件 ≤ 1000 行**；超限必须拆分或经用户拍板。
- **单次 PR 总变更量尽量 ≤ 1000 行**（合并冲突 PR 除外）；必要时拆分提交。
- 关键模块必须附带可在本机 JVM 运行的单元测试：TreeSessionStore、命令解析器、ModelMemoryCoordinator 状态机、工具 JSON 解析器。
- `:core-harness` 保持纯 Kotlin（无 Android 框架依赖），保证可独立单测。
- 文档随代码及时更新；架构事实表修改需同步 docs/ARCHITECTURE.md。

## 并行子代理写域隔离

- 多子代理并发写操作必须隔离工作区：使用 git worktree 或独立临时目录，禁止在同一工作树并发 checkout。
- 各任务在共享任务板登记写域（write scopes）；写域重叠需报 Lead 协调。
- 子代理不得直接推送云端或创建 PR；须通过 Lead 黑盒测试与审查后，由 Lead 授权或代为提交。

## 文案与语言

- 终端 `help` / `/help` 与所有面向用户的状态提示、确认提示（Allow / Deny）使用清晰准确的中文。
- 代码注释与设计文档以中文为主，标识符用英文。
