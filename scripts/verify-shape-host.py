"""Verify the prototype's reflection ABI against an apktool-decoded 21053 host."""
import argparse
import re
from pathlib import Path

INTERFACES = {
    "z8/q": {
        "a()Lz8/g;", "b(I)Ljava/lang/String;", "clear()V", "d(Ljava/lang/String;)Lz8/r;",
        "destroy()V", "e()Z", "f()Lz8/u;", "g(Ljava/util/List;)Lz8/r;", "i(I)Lz8/r;",
        "j()Ljava/lang/String;", "k()Lz8/r;", "o(Ljava/lang/String;)Lz8/r;",
        "q(Lcom/mi/ime/MiInputMethodService;Lz8/e;)V", "s()Ljava/util/List;", "t()V", "u(Ly8/b;)V",
    },
    "z8/p": {"c()Lz8/r;", "h(C)Lz8/r;", "l(I)V", "m(II)Ljava/util/List;",
             "n(ILjava/lang/String;)Lnc/f;", "p(I)V", "r(I)Lz8/r;", "v()Ljava/util/List;"},
}
REQUIRED = {
    "ab/x0": [".method public final b(Lz8/g;)Lz8/q;", ":Lcom/mi/ime/MiInputMethodService;"],
    "ab/f1": [".method public final c(Lka/c;Lab/b1;)V"],
    "ab/s0": [".method public final l(Lz8/q;)V"],
    "ab/b1": [".method public abstract u()Lz8/q;", ".method public abstract o()Z",
              ".method public abstract K(Ljava/lang/String;)V", ".method public abstract y(Lz8/r;)V"],
    "ka/c": [".field public final a:I", ".field public final f:Lka/f;"],
    "ka/f": ['"CHAR"', '"BACKSPACE"', '"SPACE"'],
    "z8/g": [".super Ljava/lang/Enum;", '"PINYIN"'],
    "z8/b": [".super Ljava/lang/Enum;", '"SYSTEM"'],
    "z8/a": [".method public constructor <init>(Ljava/lang/String;Ljava/lang/String;ILz8/b;Ljava/lang/String;)V"],
    "z8/r": [".method public constructor <init>(ZLjava/lang/String;Ljava/util/List;Ljava/lang/String;ILjava/util/List;Ljava/lang/String;)V"],
    "z8/s": [".super Lz8/u;", ".method public constructor <init>(ILjava/lang/String;Ljava/lang/String;Ljava/util/List;)V"],
    "z8/t": [".super Lz8/u;", ".field public static final a:Lz8/t;"],
    "nc/f": [".method public constructor <init>(Ljava/lang/Object;Ljava/lang/Object;)V"],
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded", type=Path)
    args = parser.parse_args()

    def source(name):
        found = list(args.decoded.glob(f"smali*/{name}.smali"))
        if len(found) != 1:
            raise ValueError(f"Expected exactly one class: {name}")
        return found[0].read_text(encoding="utf-8")

    manifest = (args.decoded / "AndroidManifest.xml").read_text(encoding="utf-8")
    info = (args.decoded / "apktool.yml").read_text(encoding="utf-8")
    if 'package="com.xiaomi.type"' not in manifest or not re.search(r"versionCode:\s*['\"]?21053['\"]?\s", info):
        raise ValueError("This prototype requires com.xiaomi.type versionCode 21053")
    for name, expected in INTERFACES.items():
        actual = set(re.findall(r"^\.method public abstract (.+)$", source(name), re.MULTILINE))
        if actual != expected:
            raise ValueError(f"Interface mismatch: {name}: {actual ^ expected}")
    for name, required in REQUIRED.items():
        data = source(name)
        for token in required:
            if token not in data:
                raise ValueError(f"Missing {name}: {token}")
    if ".implements Lz8/q;" not in source("z8/p"):
        raise ValueError("Missing extended interface inheritance")
    print("PASS: host 21053, 24 interface methods, constructors, key fields and hook signatures")


if __name__ == "__main__":
    main()
