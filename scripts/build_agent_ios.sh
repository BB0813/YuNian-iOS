#!/usr/bin/env bash
# build_agent_ios.sh — lianyu-agent 的 iOS 构建（对应 Android 侧的 scripts/build_agent.ps1）
#
# 用法：
#   ./scripts/build_agent_ios.sh                  # 测试 + 编译 device/sim + 生成 Swift 绑定 + 打包 xcframework
#   ./scripts/build_agent_ios.sh --skip-test      # 跳过 cargo test
#   ./scripts/build_agent_ios.sh --sim-only       # 只构建模拟器切片（本地迭代用，快）
#   ./scripts/build_agent_ios.sh --bindings-only  # 只生成 Swift 绑定（**任意平台**，含 Windows/Linux）
#
# 产物：
#   ios/Frameworks/lianyu_agent.xcframework        （device arm64 + simulator arm64）
#   ios/Generated/LianyuAgent.swift                （UniFFI 生成的 Swift API）
#   ios/Generated/lianyu_agentFFI.h / .modulemap   （C FFI 层）
#
# 前置条件：
#   --bindings-only 之外的模式**必须在 macOS 上运行**（需要 iOS SDK 与 clang）：
#   ring 的 ARMv8 汇编与 libsqlite3-sys 的 bundled SQLite C 源码都要靠它编译，
#   这是「必须有一台 Mac 或 macOS CI」的根本原因。
#
#   --bindings-only 只需要 rustup + cargo：它用 **host** 库（.so/.dll/.dylib）
#   提取 UNIFFI_META_* 元数据。元数据与平台无关，因此 Windows 上也能产出
#   与 macOS 完全一致的 Swift 绑定 —— 这一点让「签名一致性」可以在
#   便宜的非 macOS runner 上校验（见 .github/workflows/ios-agent.yml 的 contract-checks）。
#
# 注意：与 Android 侧共享同一份 Rust 源码，本脚本不修改任何 .rs 文件。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_NATIVE="$REPO_ROOT/agent-native"
OUT_XCFRAMEWORK="$REPO_ROOT/ios/Frameworks/lianyu_agent.xcframework"
OUT_BINDINGS="$REPO_ROOT/ios/Generated"
LIB_NAME="liblianyu_agent"

SKIP_TEST=0
SIM_ONLY=0
BINDINGS_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --skip-test)     SKIP_TEST=1 ;;
    --sim-only)      SIM_ONLY=1 ;;
    --bindings-only) BINDINGS_ONLY=1 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;35m[ios-agent]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[ios-agent] 失败：\033[0m %s\n' "$*" >&2; exit 1; }

# 定位 host 动态库，供 --bindings-only 用。
# 命名跨平台不一致：Linux/macOS 为 liblianyu_agent.so/.dylib，Windows 为 lianyu_agent.dll（无 lib 前缀）。
find_host_lib() {
  for candidate in \
    "$AGENT_NATIVE/target/debug/${LIB_NAME}.so" \
    "$AGENT_NATIVE/target/debug/${LIB_NAME}.dylib" \
    "$AGENT_NATIVE/target/debug/${LIB_NAME}.dll" \
    "$AGENT_NATIVE/target/debug/lianyu_agent.dll"; do
    [ -f "$candidate" ] && { echo "$candidate"; return 0; }
  done
  return 1
}

# ── 仅生成绑定（跨平台）────────────────────────────────────────
if [ "$BINDINGS_ONLY" -eq 1 ]; then
  command -v cargo >/dev/null || die "未找到 cargo，请先安装 rustup"
  cd "$AGENT_NATIVE"
  log "构建 host 库（供 bindgen 提取元数据）..."
  cargo build --lib
  HOST_LIB="$(find_host_lib)" || die "找不到 host 动态库（target/debug/${LIB_NAME}.*）"

  mkdir -p "$OUT_BINDINGS"
  log "生成 Swift 绑定 → $OUT_BINDINGS"
  cargo run --features cli-bin --bin uniffi-bindgen -- generate \
    --library "$HOST_LIB" \
    --language swift \
    --out-dir "$OUT_BINDINGS"

  [ -s "$OUT_BINDINGS/LianyuAgent.swift" ] || die "LianyuAgent.swift 为空 —— 检查 Cargo.toml 的 strip 是否被改成了 true"
  log "完成：$(ls -1 "$OUT_BINDINGS" | tr '\n' ' ')"
  log "下一步可运行：python ios/Tools/verify_swift_conformance.py"
  exit 0
fi

# ── 以下均需 macOS ─────────────────────────────────────────────
[ "$(uname -s)" = "Darwin" ] || die "该模式必须在 macOS 上运行（需要 iOS SDK）。若只需生成 Swift 绑定，请用 --bindings-only。当前：$(uname -s)"
command -v xcode-select >/dev/null || die "未找到 Xcode 命令行工具"
xcode-select -p >/dev/null 2>&1 || die "Xcode 未选择，请先执行：sudo xcode-select -s /Applications/Xcode.app"
command -v cargo >/dev/null || die "未找到 cargo，请先安装 rustup"
command -v rustup >/dev/null || die "未找到 rustup"

SDK_VERSION="$(xcrun --sdk iphoneos --show-sdk-version 2>/dev/null || echo '?')"
log "Xcode SDK: iphoneos $SDK_VERSION"
log "cargo: $(cargo --version)"

# ── 1. 安装 iOS target ─────────────────────────────────────────
DEVICE_TARGET="aarch64-apple-ios"
SIM_TARGET="aarch64-apple-ios-sim"   # Apple Silicon 模拟器

if [ "$SIM_ONLY" -eq 1 ]; then
  TARGETS=("$SIM_TARGET")
else
  TARGETS=("$DEVICE_TARGET" "$SIM_TARGET")
fi

for t in "${TARGETS[@]}"; do
  if ! rustup target list --installed | grep -qx "$t"; then
    log "安装 target: $t"
    rustup target add "$t"
  else
    log "target 已就绪: $t"
  fi
done

# ── 2. 宿主编译 + 单测（与 Android 侧同一套 189 个用例）─────────
cd "$AGENT_NATIVE"
if [ "$SKIP_TEST" -eq 0 ]; then
  log "cargo test（宿主）..."
  cargo test
else
  log "跳过 cargo test（--skip-test）"
fi

# ── 3. 交叉编译 ────────────────────────────────────────────────
for t in "${TARGETS[@]}"; do
  log "cargo build --release --target $t ..."
  cargo build --release --target "$t"
done

# ── 4. 生成 Swift 绑定 ─────────────────────────────────────────
# 用 device 切片的 Mach-O dylib 提取 UNIFFI_META_* 元数据。
# ⚠️ 陷阱：Cargo.toml 的 release profile 必须是 strip = "debuginfo"，不能是 strip = true。
#    strip = true 会移除 .symtab，而 --library 模式靠遍历符号表拿元数据 —— 结果是
#    退出码 0、静默产出空绑定。
METADATA_LIB="$AGENT_NATIVE/target/$DEVICE_TARGET/release/${LIB_NAME}.dylib"
if [ ! -f "$METADATA_LIB" ]; then
  # --sim-only 场景下回退到模拟器切片
  METADATA_LIB="$AGENT_NATIVE/target/$SIM_TARGET/release/${LIB_NAME}.dylib"
fi
[ -f "$METADATA_LIB" ] || die "找不到用于提取元数据的 dylib：$METADATA_LIB"

mkdir -p "$OUT_BINDINGS"
log "生成 Swift 绑定 → $OUT_BINDINGS"
cargo run --features cli-bin --bin uniffi-bindgen -- generate \
  --library "$METADATA_LIB" \
  --language swift \
  --out-dir "$OUT_BINDINGS"

# 空产出守卫：bindgen 在 strip 配置错误时会静默产出空结果，这里强制校验
[ -s "$OUT_BINDINGS/LianyuAgent.swift" ] || die "LianyuAgent.swift 为空 —— 检查 Cargo.toml 的 strip 是否被改成了 true"
[ -s "$OUT_BINDINGS/lianyu_agentFFI.h" ] || die "lianyu_agentFFI.h 缺失"
log "绑定文件：$(ls -1 "$OUT_BINDINGS" | tr '\n' ' ')"

# ── 5. 打包 xcframework ────────────────────────────────────────
if [ "$SIM_ONLY" -eq 1 ]; then
  log "跳过 xcframework 打包（--sim-only）"
else
  STATIC_DEVICE="$AGENT_NATIVE/target/$DEVICE_TARGET/release/${LIB_NAME}.a"
  STATIC_SIM="$AGENT_NATIVE/target/$SIM_TARGET/release/${LIB_NAME}.a"
  [ -f "$STATIC_DEVICE" ] || die "缺少 device 静态库：$STATIC_DEVICE"
  [ -f "$STATIC_SIM" ]    || die "缺少 simulator 静态库：$STATIC_SIM"

  rm -rf "$OUT_XCFRAMEWORK"
  mkdir -p "$(dirname "$OUT_XCFRAMEWORK")"

  # 把 FFI 头与 modulemap 放进各自的切片目录，xcframework 才能被发现
  STAGE="$(mktemp -d)"
  trap 'rm -rf "$STAGE"' EXIT
  for slice in device sim; do
    mkdir -p "$STAGE/$slice/Headers"
    cp "$OUT_BINDINGS/lianyu_agentFFI.h" "$STAGE/$slice/Headers/"
    cp "$OUT_BINDINGS/lianyu_agentFFI.modulemap" "$STAGE/$slice/Headers/module.modulemap"
  done
  cp "$STATIC_DEVICE" "$STAGE/device/${LIB_NAME}.a"
  cp "$STATIC_SIM"    "$STAGE/sim/${LIB_NAME}.a"

  log "xcodebuild -create-xcframework → $OUT_XCFRAMEWORK"
  xcodebuild -create-xcframework \
    -library "$STAGE/device/${LIB_NAME}.a" -headers "$STAGE/device/Headers" \
    -library "$STAGE/sim/${LIB_NAME}.a"    -headers "$STAGE/sim/Headers" \
    -output "$OUT_XCFRAMEWORK"
fi

log "完成。"
log "  绑定：$OUT_BINDINGS"
[ "$SIM_ONLY" -eq 1 ] || log "  框架：$OUT_XCFRAMEWORK"
log "下一步：cd ios && xcodegen generate && open YuNian.xcodeproj"
