#!/usr/bin/env bash
# 构建 dsh-companion。
#
# ## 为什么需要这个脚本，而不是直接用 ./gradlew
#
# `gradle/wrapper/gradle-wrapper.properties` 里的 distributionUrl 指向
# `services.gradle.org`。该地址在国内网络下不可达，而 Gradle wrapper 在
# 下载失败时**不会报错退出，而是静默挂起**（实测：进程 CPU 0.3%、零网络连接、
# 无 daemon 日志，16 分钟无任何输出）。
#
# 本机已缓存完整的 Gradle 9.5.0（来自 mirrors.cloud.tencent.com 镜像）。
# 本脚本直接调用该缓存中的 gradle 二进制，绕开 wrapper 的下载校验，
# 从而既不修改仓库内的 distributionUrl（保持与上游一致、便于合并），
# 又能离线可靠构建。
#
# 用法：
#   scripts/build.sh                    # 默认 assembleDebug
#   scripts/build.sh assembleRelease    # 指定任务
#   scripts/build.sh :app:testDebugUnitTest
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${DSH_ANDROID_TOOLCHAIN:-/media/wangke/OFFICE/workspace/android-toolchain}"

export JAVA_HOME="$TOOLCHAIN/jdk17"
export ANDROID_HOME="$TOOLCHAIN/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# Gradle 用户目录（发行版 + 依赖缓存）取**真有发行版**的那份。
#
# 为什么不能写死工具链里那份：`$TOOLCHAIN/gradle-home` 的 wrapper/dists 下只留了一个
# **空的**发行版目录（没有 gradle-9.5.0/bin/gradle），于是 wrapper 转去
# services.gradle.org 下载 —— 国内网络下它不报错、只是**静默挂起**
# （实测 16 分钟零输出、CPU 0.3%，正是本脚本开头注释描述的症状）。
# 家目录那份带完整发行版与 2.7G 依赖缓存，优先用它；工具链那份真有货时才用它。
find_gradle_bin_in() {
  local home="$1" candidate
  for candidate in "$home/wrapper/dists/gradle-9.5.0-bin"/*/gradle-9.5.0/bin/gradle; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

pick_gradle_home() {
  local home
  for home in "$TOOLCHAIN/gradle-home" "$HOME/.gradle"; do
    if find_gradle_bin_in "$home" >/dev/null 2>&1; then
      printf '%s\n' "$home"
      return 0
    fi
  done
  printf '%s\n' "$TOOLCHAIN/gradle-home"
}

GRADLE_USER_HOME="$(pick_gradle_home)"
export GRADLE_USER_HOME

if GRADLE_BIN="$(find_gradle_bin_in "$GRADLE_USER_HOME")"; then
  echo "[build] 使用缓存 Gradle：$GRADLE_BIN（GRADLE_USER_HOME=$GRADLE_USER_HOME）"
else
  echo "[build] 未找到缓存 Gradle，回退 gradlew（可能触发网络下载）" >&2
  GRADLE_BIN="$REPO_ROOT/gradlew"
fi

# Gradle 对同一项目目录是互斥的：并发调用会互相抢锁或直接失败。
# 多个协作者（含自动化代理）共享本机时，统一经 flock 串行化。
LOCK_FILE="${DSH_BUILD_LOCK:-/media/wangke/OFFICE/workspace/.dsh-companion-build.lock}"

# 本机无法访问 maven.google.com，需要镜像重定向。
# 注意用的是**项目自带**的 scripts/init-mirrors.gradle，而不是工具链里那份：
# 工具链版本会往 project 级仓库追加，与本项目
# RepositoriesMode.FAIL_ON_PROJECT_REPOS 冲突并直接导致构建失败。
INIT_SCRIPT="$REPO_ROOT/scripts/init-mirrors.gradle"
GRADLE_ARGS=()
if [ -f "$INIT_SCRIPT" ]; then
  GRADLE_ARGS+=(--init-script "$INIT_SCRIPT")
  echo "[build] 使用依赖镜像脚本：$INIT_SCRIPT"
fi

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
  TASKS=(assembleDebug)
fi

cd "$REPO_ROOT"
echo "[build] 任务：${TASKS[*]}"
exec flock "$LOCK_FILE" "$GRADLE_BIN" "${TASKS[@]}" "${GRADLE_ARGS[@]}" --console=plain
