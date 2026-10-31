#!/usr/bin/env python3
"""从 Simple Voice Chat 服务端 jar 里抽出纯 API 包，生成 libs/voicechat-api.jar。

为什么需要这个
--------------
SVC 的官方 maven（repo.maxhenkel.de）在部分地区不可达，而 `build.gradle` 只需要
`de/maxhenkel/voicechat/api/**` 这一棵子树里的类。所以直接从官方发布的
`voicechat-bukkit-<版本>.jar` 里把 API 包剥出来，当 **compileOnly** 本地依赖用
—— 不打进产物，运行期由客户端装的 SVC 提供。

用法
----
    python tools/extract-voicechat-api.py <voicechat-bukkit-2.6.26.jar>

产物
----
    libs/voicechat-api.jar

🔴 这个产物**不进 git**（已在 .gitignore 里）。它是第三方代码，只是编译夹具。
"""
import os
import sys
import zipfile

# 只抽这一棵子树：API 包本身。其余（实现、协议、平台适配）一概不要。
PREFIX = "de/maxhenkel/voicechat/api/"

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "libs", "voicechat-api.jar")


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2

    src = argv[1]
    if not os.path.isfile(src):
        print("❌ 找不到文件：%s" % src)
        return 1

    with zipfile.ZipFile(src) as zin:
        names = [n for n in zin.namelist() if n.startswith(PREFIX) and n.endswith(".class")]
        if not names:
            print("❌ 这个 jar 里没有 %s —— 确认给的是 voicechat 的 jar？" % PREFIX)
            print("   前 10 个条目：" + ", ".join(zin.namelist()[:10]))
            return 1

        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zout:
            for n in names:
                zout.writestr(n, zin.read(n))
            zout.writestr(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\n"
                "Implementation-Title: Simple Voice Chat API (extracted)\r\n"
                "Comment: compile-only stub, extracted from %s\r\n"
                % os.path.basename(src),
            )

    size = os.path.getsize(OUT)
    print("✓ 抽出 %d 个 API 类 → %s（%d B）" % (len(names), os.path.normpath(OUT), size))
    print("  来源：%s" % os.path.basename(src))
    print("  下一步：gradle build" if os.name != "nt" else "  下一步：gradle.bat build")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
