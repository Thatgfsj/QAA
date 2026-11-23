#!/usr/bin/env bash
# 内置冒烟模型下载脚本（GitHub Issue #6）。
#
# 用法（在任意目录均可执行）：
#   ./scripts/fetch-smoke-model.sh
#
# 将 Supra2.5-Pro-Instruct Q4_0 GGUF（67M 参数，约 40MB）从 HuggingFace 下载至
# app/src/main/assets/models/，随 APK 打包，实现安装即离线运行；
# 该二进制已被 .gitignore（**/assets/models/*.gguf）排除，不会进入版本库。
#
# 行为契约：
#   - 幂等：目标存在、非空且 GGUF 魔数校验通过时直接跳过；
#   - 断点续传：中断后重跑本脚本自动从 .part 文件续传；
#   - 断点不可续（服务器拒绝 Range / HTTP 416）时按提示删除 .part 后重跑；
#   - 任何失败均以非零退出码结束。

set -euo pipefail

readonly MODEL_URL="https://huggingface.co/liodon-ai/Supra2.5-Pro-Instruct-GGUF/resolve/main/Supra2.5-Pro-Instruct-Q4_0.gguf"
readonly MODEL_NAME="Supra2.5-Pro-Instruct-Q4_0.gguf"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TARGET_DIR="${SCRIPT_DIR}/../app/src/main/assets/models"
readonly TARGET="${TARGET_DIR}/${MODEL_NAME}"
readonly PARTIAL="${TARGET}.part"

log() { echo "[fetch-smoke-model] $*"; }
die() { echo "[fetch-smoke-model] $*" >&2; exit "${2:-1}"; }

# GGUF 魔数校验：文件头 4 字节必须为 "GGUF"。
gguf_magic_ok() {
    [ "$(head -c 4 "$1" 2>/dev/null)" = "GGUF" ]
}

# 幂等短路：目标存在、非空且魔数正确 → 跳过下载。
if [ -s "${TARGET}" ] && gguf_magic_ok "${TARGET}"; then
    log "已存在，跳过下载：${TARGET}（$(du -h "${TARGET}" | cut -f1)）"
    exit 0
fi
if [ -e "${TARGET}" ]; then
    log "目标存在但校验未通过，将重新下载覆盖：${TARGET}"
    rm -f "${TARGET}"
fi

mkdir -p "${TARGET_DIR}"
log "开始下载 ${MODEL_NAME}（约 40MB）"
log "${MODEL_URL}"

# -f：HTTP 错误码即失败；-L：跟随重定向；-C -：从 .part 已有字节断点续传。
set +e
curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -C - -o "${PARTIAL}" "${MODEL_URL}"
curl_status=$?
set -e

case "${curl_status}" in
    0)
        ;;
    33)
        # curl 33 = HTTP Range 错误：断点不可续，清理后提示重跑。
        rm -f "${PARTIAL}"
        die "断点续传不可用（curl 退出码 33）：请重跑 ./scripts/fetch-smoke-model.sh 从头下载" 33
        ;;
    22)
        # curl 22 = HTTP 请求失败（-f）：常见为 416（断点越界）或网络侧 4xx/5xx。
        if [ -s "${PARTIAL}" ]; then
            log "下载中断（HTTP 错误，curl 退出码 22），已保留断点文件：${PARTIAL}"
            log "断点不可续时请删除后重跑：rm -f ${PARTIAL}"
        else
            log "HTTP 请求失败（curl 退出码 22）：请检查网络与下载地址后重跑本脚本"
        fi
        exit 22
        ;;
    *)
        if [ -s "${PARTIAL}" ]; then
            log "下载中断（curl 退出码 ${curl_status}），已保留断点文件：${PARTIAL}"
            log "重跑本脚本将自动续传；若反复失败可执行 rm -f ${PARTIAL} 后重跑"
        else
            log "下载失败（curl 退出码 ${curl_status}）：请检查网络后重跑本脚本"
        fi
        exit "${curl_status}"
        ;;
esac

if [ ! -s "${PARTIAL}" ]; then
    rm -f "${PARTIAL}"
    die "下载结果为空文件：疑似网络中断，请重跑本脚本"
fi

mv -f "${PARTIAL}" "${TARGET}"

if ! gguf_magic_ok "${TARGET}"; then
    rm -f "${TARGET}"
    die "下载完成但 GGUF 魔数校验失败：文件可能损坏，请重跑本脚本"
fi

log "完成：${TARGET}（$(du -h "${TARGET}" | cut -f1)）"
log "该文件已被 .gitignore 排除，不会入库；可执行 ./gradlew :app:assembleDebug 打包验证"
