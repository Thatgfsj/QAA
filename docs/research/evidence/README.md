# 证据目录说明（#97 量化兼容性取证）

本目录是 `docs/research/geniex-compat-evidence.md` 的附件集，服务于 #97（乱码）与 #102（模型兼容门禁）。**全部为只读取证结果，不含新增真机操作**。

## 文件清单

| 文件 | 作用 | 一手程度 |
| --- | --- | --- |
| `../geniex-compat-evidence.md` | 主报告：复现矩阵、根因、门禁规则、用户口径 | 汇总 |
| `garbled-samples.md` | 应用内 `/export` 导出的会话轨迹原文（乱码 + 正常对照 + meta） | **一手**（真机导出，未改写） |
| `gguf-headers.md` | 10 个 GGUF 头解析结果（file_type/张量直方图/arch/pre/vocab） | **一手**（主机直接解析） |
| `model-sha256.txt` | 7 个模型文件 sha256 + 字节 + mtime | **一手**（sha256sum） |
| `crash-taxonomy.md` | 崩溃 A/B/C 分类、触发条件、与量化的解耦说明 | 二手（引 `docs/benchmarks/phase2-report.md` 与 #97） |
| `upstream-issue-draft.md` | 英文上游 issue 草稿 | 草稿（**禁止自动发布**） |
| `gguf_probe.py` | GGUF v3 头解析参考实现，#102 可直接复用 | 工具 |

## 采集环境

- 真机：小米 25102RKBEC（myron）· SM8850 · Android 17（SDK 37）· 15.4 GB · arm64-v8a · adb 49322194。
- 应用：QAA v0.1.3（`main ef461e9`），release 非 debuggable。
- 引擎：GenieX 0.10.0。
- 主机：模型文件位于 `~/llama.cpp/models/`，与真机导入文件同源（sha256 可核对）。

## 复跑方法

```bash
# GGUF 头（任意文件）
python3 docs/research/evidence/gguf_probe.py path/to/model.gguf

# 文件校验
sha256sum -b path/to/model.gguf
```

真机复现：release 包 → 模型页导入 → 载入 → 发送「Say OK only」→ 观察 30 s。乱码档位预期得到 `�` 与 ASCII 碎片混合的符号汤，进程不崩溃。

## 证据纪律

1. **不发布**：`upstream-issue-draft.md` 仅为文件交付物，是否发布由 Lead 决定（云端操作权限在 Lead）。
2. **不改 app 代码**：本目录只改 `docs/research/`。
3. **不臆造**：崩溃栈文本来自报告摘要（非 `dumpsys dropbox` 原文），已在 `crash-taxonomy.md` §5 标注缺口；真机缺失项统一进主报告 §8 待补清单。
4. **性能数字口径**：乱码回合的 tok/s（45–162）不可用作性能结论，唯一有效 4B 数字是 Q4_0 的 13.6 tok/s。
5. **脱敏**：不含 adb 序列号、本机路径、账号信息；设备型号只出现在复现环境描述中。

## 已知缺口（详见主报告 §8）

- `dumpsys dropbox` tombstone 原文（A/B 类崩溃）
- `/logs` 全量导出（现有仅 10 行）
- Q8_0 单变量闭环测试
- QUASAR-Q4_0 真机回执
- 设备模型清单完整导出（13 条）