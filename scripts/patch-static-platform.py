#!/usr/bin/env python3
"""patch-static-platform.py <static-archive> [--from IOSSIMULATOR --to MACCATALYST]

把静态库（ar 档）里每个 Mach-O 成员的 LC_BUILD_VERSION.platform 从 IOSSIMULATOR(7)
改写为 MACCATALYST(6)，minos/sdk 原样保留。逐字段 4 字节原位改写：
- 不改成员大小/偏移/符号表，无需 libtool 重打包（vtool -replace 会因
  cmdsize 24 + ntools 0 放不下 tool 表项而报 "not enough space"）；
- ar 结构（含 BSD #1/<len> 扩展名成员）整体遍历；
- 非 Mach-O 成员（__.SYMDEF 等）跳过；
- 幂等：已是目标平台的成员不动。
退出码 0 = 至少改了 1 个成员或全部已是目标平台；非 0 = 结构异常。
输出：patched=N already=N skipped=M。
"""
import struct
import sys

MAGIC64 = 0xFEEDFACF  # MH_MAGIC_64 (little-endian)
LC_BUILD_VERSION = 0x32  # 实测锚定：otool 标注的 LC_BUILD_VERSION 命令字节（非 0x2d）
PLATFORM_MAP = {
    "MACOS": 1, "IOS": 2, "TVOS": 3, "WATCHOS": 4, "BRIDGEOS": 5,
    "MACCATALYST": 6, "IOSSIMULATOR": 7, "TVOSSIMULATOR": 8,
    "WATCHOSSIMULATOR": 9, "XRROS": 10, "XRRSIMULATOR": 11,
}


def patch_macho_member(data: bytes, want: int, target: int):
    """返回 (new_bytes, patched, already)。非 MH_MAGIC_64 薄镜像返回 None。"""
    if len(data) < 64:
        return None
    magic = struct.unpack_from("<I", data, 0)[0]
    if magic != MAGIC64:
        return None
    ncmds = struct.unpack_from("<I", data, 16)[0]
    buf = bytearray(data)
    patched = already = 0
    off = 32  # sizeof mach_header_64
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", buf, off)
        if cmd == LC_BUILD_VERSION and size >= 24:
            plat = struct.unpack_from("<I", buf, off + 8)[0]
            if plat == want:
                struct.pack_into("<I", buf, off + 8, target)
                patched += 1
            elif plat == target:
                already += 1
        off += size
    return bytes(buf), patched, already


def main() -> int:
    path = sys.argv[1]
    want = PLATFORM_MAP[sys.argv[sys.argv.index("--from") + 1]] if "--from" in sys.argv else PLATFORM_MAP["IOSSIMULATOR"]
    target = PLATFORM_MAP[sys.argv[sys.argv.index("--to") + 1]] if "--to" in sys.argv else PLATFORM_MAP["MACCATALYST"]

    with open(path, "rb") as f:
        blob = f.read()
    if blob[:8] != b"!<arch>\n":
        print(f"not an ar archive: {path}", file=sys.stderr)
        return 1

    out = bytearray(blob)
    pos = 8
    patched = already = skipped = 0
    while pos + 60 <= len(blob):
        name = blob[pos:pos + 16].decode("ascii", "replace").rstrip()
        size = int(blob[pos + 48:pos + 58].decode("ascii").strip())
        data_start = pos + 60
        # BSD 扩展名成员：名字跟随在数据区前 extlen 字节
        if name.startswith("#1/"):
            extlen = int(name[3:])
            data_start += extlen
            size -= extlen
        data = blob[data_start:data_start + size]
        r = patch_macho_member(data, want, target)
        if r is None:
            skipped += 1
        else:
            new, p, a = r
            if p:
                out[data_start:data_start + size] = new
                patched += p
            already += a
        pos = data_start + size
        if pos % 2:
            pos += 1

    if patched:
        with open(path, "wb") as f:
            f.write(out)
    print(f"patched={patched} already={already} skipped={skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
