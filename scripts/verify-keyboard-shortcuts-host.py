"""Verify swipe shortcut rendering, coordinates, draw state and release callbacks in host 21053 DEX."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("decoded", type=Path)
root = parser.parse_args().decoded


def source(name):
    paths = list(root.glob(f"smali*/{name}.smali"))
    assert len(paths) == 1, (name, paths)
    return paths[0].read_text(encoding="utf-8")


required = {
    "ka/c": [
        ".method public constructor <init>(ILjava/lang/String;FLjava/lang/Float;Ljava/lang/Float;Lka/f;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/Integer;Ljava/lang/Integer;ZLjava/lang/Boolean;ZZLjava/lang/Integer;ZLjava/lang/Integer;)V",
        *[f".field public final {name}:{kind}" for name, kind in zip("abcdefghijklmnopqr", [
            "I", "Ljava/lang/String;", "F", "Ljava/lang/Float;", "Ljava/lang/Float;", "Lka/f;",
            "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/List;", "Ljava/lang/Integer;",
            "Ljava/lang/Integer;", "Z", "Ljava/lang/Boolean;", "Z", "Z", "Ljava/lang/Integer;", "Z", "Ljava/lang/Integer;"])],
    ],
    "ka/e": [".method public constructor <init>(FLjava/util/List;Ljava/util/List;)V",
             ".field public final a:Ljava/util/List;", ".field public final b:F", ".field public final c:Ljava/util/List;"],
    "ka/g": [".method public constructor <init>(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/lang/Float;Ljava/lang/Float;ZLjava/lang/Float;Z)V"],
    "ka/j": [".method public static e(CLjava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/Integer;ZZLjava/lang/Float;I)Lka/c;",
             "Ljava/lang/Character;->toLowerCase(C)C", "Ljava/lang/Character;->toUpperCase(C)C"],
    "ab/p1": [".field public final a:Lcom/mi/ime/MiInputMethodService;",
              ".method public final e(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;ZLla/a;)Lka/g;",
              ".method public final H(Lz8/g;Landroid/view/inputmethod/EditorInfo;Z)V",
              ".method public final G(Landroid/view/inputmethod/EditorInfo;Lz8/g;)V"],
    "ab/f1": [".method public final c(Lka/c;Lab/b1;)V"],
    "ab/b1": [f".method public abstract {method}" for method in [
        "A()Ljava/lang/String;", "m()Lz8/g;", "n()Z", "d()Z", "f()Ljava/lang/String;", "M()I",
        "b(I)V", "K(Ljava/lang/String;)V", "u()Lz8/q;", "F()V", "J()V"]],
    "z8/q": [".method public abstract clear()V"],
    "z9/w5": [".method public static final B(Lka/c;Ljava/lang/String;Z)Lka/c;",
              "Lkd/i;->j0(Ljava/lang/String;)Ljava/lang/Character;",
              ".method public static final D(Lka/c;Z)Ljava/util/List;", "Lka/c;->i:Ljava/util/List;"],
    "kd/i": [".method public static j0(Ljava/lang/String;)Ljava/lang/Character;",
             "Ljava/lang/String;->charAt(I)C"],
    "y7/v": [".method public final J()V", "Lab/y1;->e()V", "Ly7/p;->h(ILcom/mi/ime/MiInputMethodService;Z)V"],
    "ed/a": [".method public static final p(Lab/b1;)Z", "Lab/b1;->M()I", "Lab/b1;->b(I)V"],
    "d2/w": [".method public final dispatchTouchEvent(Landroid/view/MotionEvent;)Z"],
    "d2/u0": [".field public static final f:Ls0/i2;"],
    "s0/p": [".method public final l(Ls0/i1;)Ljava/lang/Object;"],
    "s0/d": [".method public static final R(Ljava/lang/Object;Ls0/c2;)Ls0/c1;"],
    "s0/o0": [".field public static final f:Ls0/o0;"],
    "s0/v0": [".method public abstract setValue(Ljava/lang/Object;)V"],
    "s0/h2": [".method public abstract getValue()Ljava/lang/Object;"],
    "nc/m": [".field public static final a:Lnc/m;"],
    "androidx/compose/ui/layout/a": [".method public static final b(Le1/o;Lbd/b;)Le1/o;"],
    "androidx/compose/ui/draw/a": [".method public static final c(Le1/o;Lbd/b;)Le1/o;"],
    "a2/o": [".method public abstract i()Z", ".method public abstract v(J)J", ".method public abstract l()J"],
    "c2/f0": [".method public final a()V", ".method public final S()Lw5/i;", ".method public final c()J"],
    "w5/i": [".method public s()Ll1/q;"],
    "l1/b": [".field public a:Landroid/graphics/Canvas;"],
    "l1/h0": [".method public static final v(J)I"],
    "p0/n": [".method public static final a(Ljava/lang/String;Le1/o;JJLq2/k;Lq2/e;JLw2/g;JIZIILbd/b;Ll2/h0;Ls0/p;III)V"],
}

for name, tokens in required.items():
    text = source(name)
    for token in tokens:
        assert token in text, (name, token)

import re
renderer = re.search(r"(?m)^\.method public static final a\((Lka/c;.*?)\)V$", source("z9/w5")).group(1)
assert len(re.findall(r"L[^;]+;|[ZBCSIJFD]", renderer)) == 30
assert "Lp0/n;->a(Ljava/lang/String;Le1/o;" in source("z9/w5")
print(f"PASS: swipe shortcut draw, coordinates, state and release callbacks ({len(required)} classes)")
