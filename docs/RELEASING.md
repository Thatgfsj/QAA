# 发布流程（RELEASING）

面向本仓库的**本机发布**：从版本号、签名、构建、真机安装到 GitHub Release。命令均在仓库根目录执行。

## 1. 版本号

- 单一来源：`app/build.gradle.kts` 的 `versionCode` / `versionName`。
- 规则：`versionCode` 严格递增（每次发布 +1）；`versionName` 与 `CHANGELOG.md` 顶部条目标题一致（如 `0.1.5` ↔ `## v0.1.5`）。
- 发布前先补 `CHANGELOG.md`：合并同版本的已并入条目，写面向用户的变更（不含内部重构细节），并把该版本涉及的 Issue 号列在条目里。

## 2. 签名

`app/build.gradle.kts` 的 release 签名按以下顺序解析（**都不改仓库内文件**）：

1. 环境变量：`QAA_KEYSTORE_FILE`、`QAA_KEYSTORE_PASSWORD`、`QAA_KEY_ALIAS`、`QAA_KEY_PASSWORD`
2. 未提交的 `local.properties`：`qaa.keystore.file` 等同名键
3. 兜底：`~/.android/debug.keystore`（`storepass=android`、`alias=androiddebugkey`）

**为什么默认复用 debug 证书**：本机既有安装包就是用该证书签的（SHA-256 `F2:0F:A3:DF…05:E2`）。release 包沿用同一证书，`adb install -r` 才能原地升级并保留沙盒内的模型权重、会话与设置。换成新证书会因签名不匹配只能卸载重装，`/data/user/0/com.thatgfsj.qaa/files/` 下的数据全部丢失。
因此**迁移到正式证书必须与「用户数据备份/迁移」同批做**，不能只换签名。

校验产物签名：

```bash
APKSIGNER=~/Android/Sdk/build-tools/36.0.0/apksigner
$APKSIGNER verify --print-certs app/build/outputs/apk/release/app-release.apk | grep 'SHA-256'
```

## 3. 构建

```bash
flock -w 900 /tmp/qaa-gradle.lock ./gradlew clean assembleRelease --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 冒烟模型二进制不入库，由 `scripts/fetch-smoke-model.sh` 注入 `app/src/smoke/assets/models/`，该目录**只挂 debug 变体**（#124）：`assembleRelease` 产物不含 `assets/models/`，无需先跑下载脚本；需要冒烟时构建 debug 包。可用 `unzip -l app-release.apk | grep assets/models` 自检（应为空）。
- release 为**非 debuggable**：这会影响运行时分支（见 `docs/research/geniex-compat-evidence.md` 的 A 类崩溃），冒烟必须在 release 包上做，不能用 debug 包代替。

## 4. 安装到真机（保留数据）

```bash
adb -s 49322194 install -r app/build/outputs/apk/release/app-release.apk
adb -s 49322194 shell dumpsys package com.thatgfsj.qaa | grep -E 'versionCode|versionName|flags='
```

- `-r` 保留数据；签名一致时不会要求卸载。安装后 `flags` 不应再出现 `DEBUGGABLE`。
- 手机需解锁并停留在桌面/应用内，冒烟脚本才能找到输入框（锁屏会命中 `NotificationShade`）。

## 5. 冒烟验收

```bash
~/qaa-verify/qaa-device-test.sh -p '/status' -w 20 -o /tmp/qaa-status.xml
~/qaa-verify/qaa-device-test.sh -p '/ctx 32k' -w 20
~/qaa-verify/qaa-device-test.sh -p '/tree' -w 20
```

必查项（发布门槛）：

1. 顶栏显示 `v0.1.5`，模型清单无重复条目、内置模型只有一条；
2. `/ctx` 回执含当前上下文、预算 tokens、KV 占用估算、可用/总内存，且 >8192 提示需重新载入；
3. Q4_0 模型（Spark-X2.6-4B-Q4_0）载入 → 中文回合 → 工具调用触发确认门（XML 方言路径）；
4. 非 Q4_0 量化被兼容门禁拦截并给出可执行文案；
5. `/compact` 有 before→after 回执，重复执行不再增长；
6. `/model delete` 删除后清单不再复活。

## 6. 打 tag 与 GitHub Release

```bash
git tag -a v0.1.5 -m "v0.1.5（打磨五 · 发布）" && git push origin v0.1.5
gh release create v0.1.5 app/build/outputs/apk/release/app-release.apk \
  --title "v0.1.5（打磨五 · 发布）" --notes-file /tmp/release-notes.md
```

Release notes 用 `CHANGELOG.md` 对应版本段落，末尾列出已关闭 Issue 与「已知限制」（未做项：Vulkan 引擎 #103、图像生成 #23/#24）。

## 7. 收尾

- 合并后删除对应分支与 worktree：`git worktree remove wt/<name>`、`git branch -d feat/<name>`。
- 关闭已完成的 Issue（PR 内 `Closes #N` 会自动关闭；发布类 Issue 在 Release 创建后手动关）。
