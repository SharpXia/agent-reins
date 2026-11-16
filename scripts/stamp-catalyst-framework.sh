#!/bin/bash
# stamp-catalyst-framework.sh <path-to-static-framework-binary>
#
# 为什么存在：K/N 2.4.21 没有 macabi 产物面（KT-40442 未落地——konan.properties 无
# macabi target，BinaryOptionRegistry 无 macabi 选项，binaryOptions["macabi"]="true"
# 落到编译器是 "Unknown binary option" 警告、无效），embedAndSignCatalystAppleFrameworkForXcode
# 产出的静态框架成员平台是 IOSSIMULATOR。Catalyst destination 的 ld 严格校验静态库成员的
# LC_BUILD_VERSION 平台，直接链接报
#   "building for 'macCatalyst', but linking in object file ... built for 'iOS-simulator'"。
#
# 做什么：调 scripts/patch-static-platform.py 单遍把 ar 档每个 Mach-O 成员的
# LC_BUILD_VERSION.platform 从 IOSSIMULATOR(7) 原位改写为 MACCATALYST(6)
# （4 字节字段改写，不动成员大小/偏移/符号表，无需 libtool 重打包）。
# 只处理 build/xcode-frameworks 下的嵌入式拷贝；Gradle 源框架（build/bin/...）不动，
# iOS / 模拟器链路零影响。vtool -replace 方案不可行：cmdsize 24 + ntools 0 放不下
# tool 表项，报 "not enough space to hold load commands"。
#
# 幂等 + 快路径：打完标记在框架旁落 .stamped-maccatalyst 哨兵；框架未比哨兵新
# （embed 任务没重新拷贝）时直接退出。哨兵是构建产物（不入库），丢失/过期即重打。
set -euo pipefail

FW="${1:?usage: stamp-catalyst-framework.sh <framework-binary>}"
[ -f "$FW" ] || { echo "[stamp-catalyst] framework not found: $FW" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SENTINEL="$FW.stamped-maccatalyst"
if [ -f "$SENTINEL" ] && ! [ "$FW" -nt "$SENTINEL" ]; then
  echo "[stamp-catalyst] sentinel fresh, skip: $FW"
  exit 0
fi

python3 "$SCRIPT_DIR/patch-static-platform.py" "$FW"

# 落档自检：抽一个含 LC_BUILD_VERSION 的成员让 Apple 解析器复核平台
TMP="$(mktemp -d "${TMPDIR:-/tmp}/stampcat.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
ar t "$FW" > "$TMP/members.txt"   # 落文件防 SIGPIPE：早退 break 时 ar 还在写管道
while IFS= read -r m; do
  ar p "$FW" "$m" > "$TMP/verify.o" 2>/dev/null || continue
  if xcrun vtool -show-build "$TMP/verify.o" >/dev/null 2>&1; then
    pl=$(xcrun vtool -show-build "$TMP/verify.o" | grep '^ *platform ' | awk '{print $2}')
    [ "$pl" = "MACCATALYST" ] || { echo "[stamp-catalyst] post-verify failed ($m=$pl): $FW" >&2; exit 1; }
    break
  fi
done < "$TMP/members.txt"
touch "$SENTINEL"
echo "[stamp-catalyst] stamped MACCATALYST + sentinel: $FW"
