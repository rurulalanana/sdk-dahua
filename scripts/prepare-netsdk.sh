#!/usr/bin/env bash
#
# 准备 sdk-dahua 的构建前置条件并安装到本地 Maven 仓库。
#
# 背景：大华 NetSDK 受厂商许可约束，不能随仓库分发，需要手工准备两样东西：
#   1) com.netsdk:netsdk:3.061  —— 官方 SDK 的 Java 部分（class 文件）
#   2) lib/<platform>/*.so|*.dll —— 按平台裁剪的原生库（打进 sdk-dahua 的 jar）
#
# 用法：
#   ./scripts/prepare-netsdk.sh --sdk-jar <netsdk-api.jar> --platform linux64
#   ./scripts/prepare-netsdk.sh --sdk-jar <netsdk-api.jar> --platform win64
#   ./scripts/prepare-netsdk.sh --install-only   # 只把已在 ~/.m2 的 netsdk 装好并构建
#
# 幂等：可重复执行。已 install 的 netsdk 会跳过。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

NETSDK_VERSION="3.061"
SDK_JAR=""
PLATFORM=""
INSTALL_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk-jar)    SDK_JAR="$2"; shift 2 ;;
    --platform)   PLATFORM="$2"; shift 2 ;;
    --install-only) INSTALL_ONLY=1; shift ;;
    -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

info() { printf '\033[0;36m[信息]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[警告]\033[0m %s\n' "$*"; }
err()  { printf '\033[0;31m[错误]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------- 1. 平台推断
detect_platform() {
  case "$(uname -s)" in
    Darwin) echo "darwin" ;;
    Linux)  echo "linux64" ;;
    MINGW*|MSYS*|CYGWIN*) echo "win64" ;;
    *) echo "" ;;
  esac
}

if [[ -z "$PLATFORM" ]]; then
  PLATFORM="$(detect_platform)"
  if [[ -z "$PLATFORM" ]]; then
    err "无法识别当前系统，请用 --platform 显式指定（linux64 / win64）"
    exit 1
  fi
  info "按当前系统推断平台: $PLATFORM"
fi

case "$PLATFORM" in
  linux64|win64) ;;
  darwin)
    warn "官方 NetSDK 未提供 darwin 原生库，jar 里不会带 .so；"
    warn "本机可编译，但调用大华功能需在 Linux/Windows 上运行。"
    ;;
  *) err "未知平台: $PLATFORM（支持 linux64 / win64）"; exit 2 ;;
esac

# ---------------------------------------------------------------- 2. netsdk jar
install_netsdk() {
  local jar="$1"
  [[ -f "$jar" ]] || { err "netsdk jar 不存在: $jar"; exit 1; }

  info "安装 com.netsdk:netsdk:$NETSDK_VERSION <- $jar"
  mvn -q install:install-file \
    -Dfile="$jar" \
    -DgroupId=com.netsdk \
    -DartifactId=netsdk \
    -Dversion="$NETSDK_VERSION" \
    -Dpackaging=jar
  info "netsdk 已进入本地仓库"
}

if [[ $INSTALL_ONLY -eq 0 ]]; then
  if [[ -z "$SDK_JAR" ]]; then
    err "缺少 --sdk-jar 参数（官方 SDK 的 Java 部分，如 netsdk-api.jar）"
    err "或用 --install-only 只构建已装好的 netsdk"
    exit 2
  fi
  install_netsdk "$SDK_JAR"
else
  if [[ ! -f "$HOME/.m2/repository/com/netsdk/netsdk/$NETSDK_VERSION/netsdk-$NETSDK_VERSION.jar" ]]; then
    err "本地仓库没有 netsdk:$NETSDK_VERSION，请先执行 --sdk-jar"
    exit 1
  fi
  info "复用本地仓库已有的 netsdk:$NETSDK_VERSION"
fi

# ---------------------------------------------------------------- 3. 原生库
NATIVE_DIR="lib/$PLATFORM"
if [[ -d "$NATIVE_DIR" ]] && [[ -n "$(ls -A "$NATIVE_DIR" 2>/dev/null)" ]]; then
  info "原生库已就位: $NATIVE_DIR"
  ls -1 "$NATIVE_DIR" | sed 's/^/    /'
else
  warn "$NATIVE_DIR 为空 —— 打包出的 jar 不含原生库"
  warn "需要调用大华功能时，按 docs/原生库打包.md §1 从官方 SDK 裁剪后放入该目录"
fi

# ---------------------------------------------------------------- 4. 构建
info "构建并安装 io.github.rurulalanana:sdk-dahua"
mvn -q clean install -DskipTests "-Dnetsdk.platform=$PLATFORM"

info "完成。可用以下坐标引用："
cat <<EOF

    <dependency>
      <groupId>io.github.rurulalanana</groupId>
      <artifactId>sdk-dahua</artifactId>
      <version>1.0.0</version>
    </dependency>

EOF
