# GGUF 头解析结果（#102 门禁数据源样本）

全部字段由 `evidence/gguf_probe.py` 直接读取 GGUF v3 头（magic/version/kv/tensor-info）得出，未经真机。
扫描脚本可对任意 GGUF 复跑：`python3 gguf_probe.py <file.gguf> ...`（只读，输出一行一模型 JSON）。

## 1. 汇总表

| 文件 | 字节 | file_type | 权重块类型（张量计数） | arch | pre | vocab | n_tensors | 真机结论 |
| --- | ---: | ---: | --- | --- | --- | ---: | ---: | --- |
| Supra2.5-Pro-Instruct-Q4_0（内置资产） | 43,565,760 | 2 | Q4_0×84, Q8_0×1, F32×49 | qwen3 | default | 16,384 | 134 | ✅ 正常（NPU TTFT 25 ms / 409.6 tok/s） |
| Supra2.5-Pro-Instruct-Q4_K_M（截断件） | 53,856,960 | 15 | Q5_0×72, Q8_0×13, F32×49 | qwen3 | default | 16,384 | 134 | ❌ 符号汤 |
| Spark-X2.6-4B-Q4_K_M | 3,543,441,312 | 15 | Q4_K×108, Q6_K×36, Q8_0×37, BF16×37, F32×73 | spark2_5 | spark2_5 | 131,072 | 291 | ❌ 符号汤（CPU）；NPU 长上下文另触发 B 类 abort |
| Spark-X2.6-4B-Q4_0 | 2,405,583,200 | 2 | Q4_0×216, Q6_K×1, F32×73 | spark2_5 | spark2_5 | 131,072 | 290 | ✅ 流畅中文，CPU 13.6 tok/s |
| Spark-X2.6-1.7B-Q8_0 | 1,820,113,440 | 7 | Q8_0×169, F32×57 | spark2_5 | spark2_5 | 131,072 | 226 | ❌ 符号汤（NPU 69 ms / 162.6 tok/s） |
| Huihui-Qwen3.5-4B-abliterated.Q4_K_M | 2,707,514,688 | 15 | Q4_K×203, Q5_K×24, Q6_K×22, F32×177 | qwen35 | qwen35 | 248,320 | 426 | ❌ 符号汤（CPU 45.5 tok/s） |
| MiniCPM5-2B-Q4_K_M | 1,561,318,368 | 15 | Q4_K×253, Q6_K×43, F32×85 | llama | minicpm5 | 130,560 | 381 | ❌ 符号汤（CPU 105–108 tok/s） |
| MiniCPM5-2B-Math-Q8_0 | 2,679,711,264 | 7 | Q8_0×296, F32×85 | llama | minicpm5 | 130,560 | 381 | ⬜ 未上机 |
| Qwen3.5-4B-QUASAR-Q4_0 | 2,703,509,760 | 2 | Q4_0×200, Q8_0×1, F16×48, F32×177 | qwen35 | qwen35 | 248,320 | 426 | ⬜ 真机记录待补（文件已在主机与导入清单） |

> `general.file_type` 取值与 llama.cpp `llama_ftype` 枚举一致：`2 = MOSTLY_Q4_0`、`7 = MOSTLY_Q8_0`、`15 = MOSTLY_Q4_K_M`。

## 2. 原始 JSON（脚本输出，逐行）

```json
{"file": "Supra2.5-Pro-Instruct-Q4_0.gguf", "bytes": 43565760, "magic": "GGUF", "version": 3, "n_tensors": 134, "arch": "qwen3", "name": "Supra2.5 65M SFT FINAL", "file_type": 2, "quant_version": 2, "tok_model": "gpt2", "pre": "default", "vocab": 16384, "quant_hist": {"F32": 49, "Q8_0": 1, "Q4_0": 84}, "sample": [["output_norm.weight", [640], "F32"], ["token_embd.weight", [640, 16384], "Q8_0"], ["blk.0.attn_k.weight", [640, 640], "Q4_0"]]}
{"file": "Qwen3.5-4B-QUASAR-Q4_0.gguf", "bytes": 2703509760, "magic": "GGUF", "version": 3, "n_tensors": 426, "arch": "qwen35", "name": "Materialized", "file_type": 2, "quant_version": 2, "pre": "qwen35", "vocab": 248320, "quant_hist": {"Q8_0": 1, "F32": 177, "F16": 48, "Q4_0": 200}, "sample": [["token_embd.weight", [2560, 248320], "Q8_0"], ["blk.0.attn_norm.weight", [2560], "F32"], ["blk.0.ssm_a", [32], "F32"]]}
{"file": "Spark-X2.6-4B-Q4_0.gguf", "bytes": 2405583200, "magic": "GGUF", "version": 3, "n_tensors": 290, "arch": "spark2_5", "name": "Spark X2.6 4B", "file_type": 2, "quant_version": 2, "pre": "spark2_5", "vocab": 131072, "quant_hist": {"F32": 73, "Q6_K": 1, "Q4_0": 216}, "sample": [["output_norm.weight", [2560], "F32"], ["token_embd.weight", [2560, 131072], "Q6_K"], ["blk.0.attn_gate.weight", [2560, 16], "Q4_0"]]}
{"file": "Spark-X2.6-4B-Q4_K_M.gguf", "bytes": 3543441312, "magic": "GGUF", "version": 3, "n_tensors": 291, "arch": "spark2_5", "name": "Dense F32", "file_type": 15, "quant_version": 2, "pre": "spark2_5", "vocab": 131072, "quant_hist": {"Q8_0": 37, "F32": 73, "BF16": 37, "Q6_K": 36, "Q4_K": 108}, "sample": [["output.weight", [2560, 131072], "Q8_0"], ["output_norm.weight", [2560], "F32"], ["token_embd.weight", [2560, 131072], "BF16"]]}
{"file": "Spark-X2.6-1.7B-Q8_0.gguf", "bytes": 1820113440, "magic": "GGUF", "version": 3, "n_tensors": 226, "arch": "spark2_5", "name": "1p7b_opd_new_1025", "file_type": 7, "quant_version": 2, "pre": "spark2_5", "vocab": 131072, "quant_hist": {"F32": 57, "Q8_0": 169}, "sample": [["output_norm.weight", [2048], "F32"], ["token_embd.weight", [2048, 131072], "Q8_0"], ["blk.0.attn_gate.weight", [2048, 8], "Q8_0"]]}
{"file": "Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf", "bytes": 2707514688, "magic": "GGUF", "version": 3, "n_tensors": 426, "arch": "qwen35", "name": "Huihui Qwen3.5 4B Abliterated", "file_type": 15, "quant_version": 2, "pre": "qwen35", "vocab": 248320, "quant_hist": {"F32": 177, "Q6_K": 22, "Q4_K": 203, "Q5_K": 24}, "sample": [["output_norm.weight", [2560], "F32"], ["token_embd.weight", [2560, 248320], "Q6_K"], ["blk.0.attn_gate.weight", [2560, 4096], "Q4_K"]]}
{"file": "MiniCPM5-2B-Q4_K_M.gguf", "bytes": 1561318368, "magic": "GGUF", "version": 3, "n_tensors": 381, "arch": "llama", "name": "MiniCPM5 2.6B", "file_type": 15, "quant_version": 2, "pre": "minicpm5", "vocab": 130560, "quant_hist": {"Q6_K": 43, "F32": 85, "Q4_K": 253}, "sample": [["output.weight", [2048, 130560], "Q6_K"], ["output_norm.weight", [2048], "F32"], ["token_embd.weight", [2048, 130560], "Q4_K"]]}
{"file": "MiniCPM5-2B-Math-Q8_0.gguf", "bytes": 2679711264, "magic": "GGUF", "version": 3, "n_tensors": 381, "arch": "llama", "name": "MiniCPM5 2B Math", "file_type": 7, "quant_version": 2, "pre": "minicpm5", "vocab": 130560, "quant_hist": {"Q8_0": 296, "F32": 85}, "sample": [["output.weight", [2048, 130560], "Q8_0"], ["output_norm.weight", [2048], "F32"], ["token_embd.weight", [2048, 130560], "Q8_0"]]}
```

## 3. 解析口径说明

- `magic = GGUF`、`version = 3` 全部通过，排除容器层损坏。
- `quant_hist` 为**张量个数**而非字节数；块大小差异（Q4_0 32 元素/块 vs Q4_K 256 元素/超块）需按字节折算，门禁只用类型与角色，不用计数阈值绝对值。
- `sample` 仅取前 3 个张量，用于人工核对角色（`token_embd` / `output_norm` / 各层 `blk.N.*`）。