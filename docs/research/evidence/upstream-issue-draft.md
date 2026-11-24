# 上游 Issue 草稿（English）— **未发布，待 Lead 审定**

> 目标仓库：GenieX 官方仓库（Qualcomm AI Hub / geniex）；路径与模板以 Lead 确认的仓库为准。
> 本文件只是草稿：**禁止自动发布**。发布前需 Lead 复核（附件、版本号、是否包含内部信息）。

## Suggested title

`GenieX 0.10.0 GGUF: garbled output for K-quants (Q4_K_M) on SM8850 while Q4_0 works — same model, only quantization differs`

## Body

### Summary

With GenieX 0.10.0 (llama_cpp backend, GGUF v3) on a Snapdragon 8 Elite Gen 5 (SM8850) device, any K-quantized model (general.file_type = MOSTLY_Q4_K_M, i.e. Q4_K/Q5_K/Q6_K tensors) produces pure symbol soup on the CPU path, while Q4_0 files of the *same* model produce coherent output. The process stays alive and reports normal token rates, so this is a numerical/dequantization defect, not a crash or a prompt-formatting issue.

### Environment

| Item | Value |
| --- | --- |
| Device | Xiaomi 25102RKBEC (myron), Snapdragon 8 Elite Gen 5 (SM8850) |
| OS | Android 17 (SDK 37), HyperOS, arm64-v8a, 15.4 GB RAM |
| GenieX | 0.10.0 (AAR; libllama.so GGUF path) |
| App | QA test app, release build, debuggable=false, minSdk 26 |
| Model container | GGUF version 3, tokenizer gpt2/sentencepiece as shipped in file |
| Runtime id / compute unit | llama_cpp, cpu (also reproduced via npu in some cases before an unrelated HTP abort) |
| nCtx | 4096 |

### Steps to reproduce

1. Import the two GGUF files below via the local model manager (LOCALFS), or load them directly by path.
2. Load `Spark-X2.6-4B-Q4_0.gguf` with compute_unit=cpu, nCtx=4096, generate 64 tokens for the prompt `Say OK only`.
3. Repeat with `Spark-X2.6-4B-Q4_K_M.gguf` (same base model, same tokenizer, same chat template, only quantization differs).
4. Repeat steps 2-3 with the 67M control pair `Supra-Q4_0` / `Supra-Q4_K_M` (arch qwen3, pre-tokenizer default, vocab 16384).

### Expected

Both files produce comparable, coherent English/Chinese output; quantization only affects quality slightly.

### Actual

Q4_0: coherent output, e.g. `我是QAA手机端AI助手，专注于为用户提供便捷、准确的信息服务与问题解答…` (Spark 4B, Chinese prompt) and readable English for the 67M model.

Q4_K_M: symbol soup with no CJK characters, e.g. (verbatim, 42-180 chars per turn):

```text
#ics!ask:2 access2.2.2P@@@@# pipelineamb:%.2P.5.2P?2:--Pip?PPq+2 us.puP:2L!C!! moment:+!? glucose5 alpha Py Back-2A2PineP Quick:P Communication section: pipeline Question loop pipeline further perceiveode Pider#:qu navigation pir:ced drive drink
```

Process stays alive; streaming completes normally; reported rate 45-162 tok/s (inflated because the computation is effectively garbage). No exception, no native abort.

### Controlled comparisons (all on the same device, release build, CPU path)

| Model pair | arch / pre-tokenizer / vocab | Q4_0 | Q4_K_M |
| --- | --- | --- | --- |
| Supra2.5-Pro-Instruct 67M | qwen3 / default / 16,384 | coherent | symbol soup |
| Spark-X2.6-4B | spark2_5 / spark2_5 / 131,072 | coherent | symbol soup |
| Huihui-Qwen3.5-4B-abliterated | qwen35 / qwen35 / 248,320 | (no Q4_0 file) | symbol soup |
| MiniCPM5-2B | llama / minicpm5 / 130,560 | (no Q4_0 file) | symbol soup |

All K-quant files: `general.file_type = 15`, tensor histogram contains Q4_K/Q5_K/Q6_K blocks. All Q4_0 files: `general.file_type = 2`, matmul tensors are Q4_0 blocks.

Because the pairs share architecture, tokenizer and vocabulary, the pre-tokenizer / architecture-support hypotheses are ruled out for the Q4_0-vs-Q4_K_M axis.

### Additional observation 1 — pre-tokenizer silently falls back

`MiniCPM5-2B-Q4_K_M.gguf` declares `general.architecture = llama` with `tokenizer.ggml.pre = minicpm5`. The string `minicpm5` is absent from libllama.so (only `minicpm`, `minicpm3` present), yet loading succeeds with no warning and the model produces symbol soup. Suggest emitting an explicit error/warning when `tokenizer.ggml.pre` is unknown instead of silently falling back.

### Additional observation 2 — HTP path aborts on KV-cache update (separate issue)

On the npu/HTP path, long-context generation aborts natively: `llama_kv_cache::update` -> `ggml_backend_sched_split_graph` -> `ggml_abort`. It reproduces even with the 67M Q4_0 model at 3647/4096 used tokens, so it is not model- or quantization-specific. CPU path is stable. Happy to file this separately with dropbox tombstone output.

### Attachments

1. `garbled-output-session.jsonl` (verbatim app session export, 6 garbled turns + model load timeline).
2. `gguf-headers.txt` (per-file `general.file_type`, tensor type histogram, arch, pre-tokenizer, vocab).
3. `model-sha256.txt` (sha256 of every file referenced).
4. On request: 5-10 s screen recording, `dumpsys dropbox` tombstone for the HTP abort.

### What would help

- Confirm whether the K-quant dequantization kernels (GGML_TYPE_Q4_K / Q5_K / Q6_K) are correctly wired for the Snapdragon CPU backend in 0.10.0.
- Confirm whether Q8_0 is also affected (we have one confounded data point: an 8-bit file on an unsupported pre-tokenizer also garbles).
- A version/commit hash of the vendored llama.cpp fork would let us pinpoint the divergence.

---

## 发布前检查清单（Lead）

- [ ] 仓库/模板确认（标题长度、模板字段）
- [ ] 设备型号、OS 版本是否可公开
- [ ] 附件脱敏（无 adb 序列号、无本地路径、无用户名）
- [ ] 版本号与 commit 复核（0.10.0 + 应用 ef461e9）
- [ ] 是否同时提 HTP 长上下文 issue（建议拆分，避免混题）