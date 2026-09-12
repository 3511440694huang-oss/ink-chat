#!/bin/bash
# ink-chat 构建脚本 —— 在 Operit Ubuntu 终端中执行
# 用法：
#   bash build.sh                      # 默认构建 debug APK
#   bash build.sh :app:assembleRelease # 构建 release APK
#   bash build.sh :app:compileDebugKotlin  # 只过 Kotlin 编译（最快暴露代码错误）

PROJ="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_HOME=/storage/emulated/0/WORK/02-工具链/android-sdk
GRADLE=/storage/emulated/0/WORK/02-工具链/toolchain/gradle-8.11.1/bin/gradle
TASK="${1:-:app:assembleDebug}"

echo "== ink-chat 构建： $TASK"
echo "== 工程目录： $PROJ"
cd "$PROJ" || exit 1

bash "$GRADLE" "$TASK" 2>&1 | tee /tmp/ink-build.log
RC=${PIPESTATUS[0]}
echo "== 退出码： $RC（日志：/tmp/ink-build.log）"
exit $RC
