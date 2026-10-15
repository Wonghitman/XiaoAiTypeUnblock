"""Verify independent keyboard picker integration against decoded host 21053 DEX."""
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
    "z9/g6": [".field public final a:Ljava/lang/String;", ".field public final c:I",
              ".method public constructor <init>(ILjava/lang/String;Ljava/lang/String;)V"],
    "oc/m": [".method public static T0(Ljava/lang/Iterable;I)Ljava/util/ArrayList;"],
    "z9/c3": [".method public final m(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
              "Loc/n;->I0([Ljava/lang/Object;)Ljava/util/List;",
              "Loc/m;->T0(Ljava/lang/Iterable;I)Ljava/util/ArrayList;",
              '"qwerty_chinese"', '"t9_chinese"', '"handwriting_chinese"', '"stroke_chinese"'],
    "z9/u7": [".method public static final a0(Ljava/lang/String;Lbd/b;Lkotlin/jvm/functions/Function0;Ljava/util/Set;Ls0/p;I)V"],
    "bd/b": [".method public abstract g(Ljava/lang/Object;)Ljava/lang/Object;"],
    "gb/u0": [".field public final synthetic b:Lcom/mi/ime/MiInputMethodService;",
              "Lcom/mi/ime/MiInputMethodService;->switchEngine$app_iflytekFullRelease(Lz8/g;)V"],
    "ab/x0": [".method public final f(Lz8/g;Lbd/b;Lbd/b;Z)V", "Lz8/q;->destroy()V",
              "Lab/x0;->b(Lz8/g;)Lz8/q;"],
    "qh/c": [".method public static final y(Ldg/u;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lbd/b;)V"],
    "db/l": [".method public final h(Ljava/lang/String;)V", '"keyboard_type_group"', '"kbd_type_pinyin"'],
    "s4/s": [".method public final f(Ljava/lang/String;)Landroidx/preference/Preference;"],
    "androidx/preference/Preference": [".method public final D(Ljava/lang/String;)V",
                                       ".method public G(Ljava/lang/String;)V", ".field public j:I"],
    "androidx/preference/PreferenceGroup": [".method public L(Landroidx/preference/Preference;)Z"],
    "miuix/preference/SingleChoicePreference": [".method public constructor <init>(Landroid/content/Context;)V",
                                               ".field public n0:Ljava/lang/String;"],
    "com/mi/ime/MiInputMethodService": [".field private static volatile instance:Lcom/mi/ime/MiInputMethodService;",
                                      ".method public final isEngineManagerReady$app_iflytekFullRelease()Z",
                                      ".method public final switchEngine$app_iflytekFullRelease(Lz8/g;)V"],
}

for name, tokens in required.items():
    text = source(name)
    for token in tokens:
        assert token in text, (name, token)

print(f"PASS: independent keyboard picker, native settings and engine lifecycle ABI ({len(required)} classes)")
