#!/usr/bin/env bash
# 拉取 llama.cpp 源码到 mobile 工程的 native 目录（在 PC 上运行一次即可）
set -e
cd "$(dirname "$0")/.."
if [ -d app/src/main/cpp/llama.cpp ]; then
  echo "llama.cpp 已存在，跳过（如需更新请删除该目录后重跑）"
  exit 0
fi
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
echo "完成。用 Android Studio 打开 mobile/ 工程即可构建。"
