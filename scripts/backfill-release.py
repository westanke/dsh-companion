#!/usr/bin/env python3
"""把发版产物的真实数字回填进 README 双站。

用法：
    scripts/backfill-release.py <versionName> <versionCode> <bytes> <sha256> <apkName>

为什么单独成脚本（而不是塞在 release.sh 里）：
1. 它可以**单独跑、单独验证** —— 回填改的是发布页面的文档，改错的代价比改代码高；
2. release.sh 里的 heredoc 嵌套会与 shell 的定界符打架，抽出来两边都干净。

## 只动锚点定位的几处，绝不碰历史版本号

踩过的坑：最初用 `v2\\.\\d+\\.\\d+` 全局替换，会把 README 里 v2.2.41 / v2.2.40
这些**历史版本记录**一并改成当前版本 —— 那不是回填，那是篡改历史。
所以这里每一处替换都带明确上下文（「当前版本：」「versionCode」「tag `」等），
匹配不到就一个字节都不动。
"""
import re
import sys
import pathlib


def backfill(text: str, ver: str, code: str, size: int, sha: str, apk: str) -> str:
    """返回回填后的文本。纯函数，便于单测。"""
    size_fmt = f"{size:,}"
    reps = [
        # 当前版本行（中/英）
        (r"(- 当前版本：`v)[\d.]+(`（`versionCode` )\d+(）)", rf"\g<1>{ver}\g<2>{code}\g<3>"),
        (r"(- Version: `v)[\d.]+(` \(`versionCode` )\d+(\))", rf"\g<1>{ver}\g<2>{code}\g<3>"),
        # APK 文件名（中文应用名 + 版本）
        (r"DSH伴侣-companion-v[\d.]+\.apk", apk),
        # 体积，带千分位（中/英）
        (r"\*\*[\d,]+ 字节\*\*", f"**{size_fmt} 字节**"),
        (r"\*\*[\d,]+ bytes\*\*", f"**{size_fmt} bytes**"),
        # SHA-256：只改前面明确写着 SHA-256 的那一处，不碰文中其它 64 位 hex
        (r"(SHA-256\s*`)[0-9a-f]{64}(`)", rf"\g<1>{sha}\g<2>"),
        # 「已发 …（tag vX）」里的 tag 引用
        (r"(tag `v)[\d.]+(`)", rf"\g<1>{ver}\g<2>"),
    ]
    out = text
    for pat, rep in reps:
        out = re.sub(pat, rep, out)
    return out


def main() -> int:
    if len(sys.argv) != 6:
        print("用法: backfill-release.py <ver> <code> <bytes> <sha256> <apkName>", file=sys.stderr)
        return 2
    ver, code, size_s, sha, apk = sys.argv[1:6]
    size = int(size_s)

    changed = []
    for path in ("README.md", "README.en.md"):
        p = pathlib.Path(path)
        if not p.exists():
            continue
        before = p.read_text(encoding="utf-8")
        after = backfill(before, ver, code, size, sha, apk)
        if after != before:
            p.write_text(after, encoding="utf-8")
            changed.append(path)

    for c in changed:
        print(f"\033[32m  ✅ 已回填 {c}\033[0m")
    if not changed:
        print("\033[33m  ⚠️ README 双站没有需要改的地方（已是当前值？）\033[0m")

    # 交叉检查：两站里必须都能看到当前版本、体积、SHA。缺一项就说明漂移了。
    problems = []
    for path in ("README.md", "README.en.md"):
        p = pathlib.Path(path)
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        if f"v{ver}" not in t:
            problems.append(f"{path}: 找不到 v{ver}")
        if sha not in t:
            problems.append(f"{path}: 找不到 SHA-256")
        if f"{size:,}" not in t:
            problems.append(f"{path}: 找不到体积 {size:,}")
    if problems:
        for x in problems:
            print(f"\033[31m  ❌ {x}\033[0m")
        return 1
    print("\033[32m  ✅ 双站都能查到当前版本 / 体积 / SHA-256\033[0m")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
