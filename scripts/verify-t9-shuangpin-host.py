"""Verify the native nine-key ShuangPin path from decoded DEX without a device."""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("decoded", type=Path)
root = parser.parse_args().decoded


def methods(path):
    return re.findall(r"(?ms)^\.method[^\n]*\n.*?^\.end method", path.read_text(encoding="utf-8"))


# These are the same string/signature anchors used by HostFingerprints.t9Shuangpin.
matches = []
for directory in root.glob("smali*"):
    for path in directory.rglob("*.smali"):
        text = path.read_text(encoding="utf-8")
        if '"IflytekT9Engine initialized"' not in text:
            continue
        for method in methods(path):
            if all(f'"{token}"' in method for token in (
                "IflytekT9Engine initialized", "shuangpin_enabled", "shuangpin_scheme"
            )) and re.search(r"\(Lcom/mi/ime/MiInputMethodService;L[^;]+;\)V", method.splitlines()[0]):
                matches.append((path, method))

assert len(matches) == 1, [(str(path), method.splitlines()[0]) for path, method in matches]
path, initialize = matches[0]
assert "->INPUT_TYPE_CN_9:" in initialize
assert "Lcom/iflytek/depend/common/pinyin/IPinYinEngine;->updateShuangPinType(" in initialize

for signature in ("(Ljava/lang/String;Z)Z", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"):
    calls = set(re.findall(r"invoke-static[^\n]*, (L[^\s]+" + re.escape(signature) + r")$", initialize, re.M))
    assert len(calls) == 1, (signature, calls)
    print(f"PASS: unique native preference getter {next(iter(calls))}")

# Validate native ID-to-SDK mappings independently of the module's enum order.
for scheme in ("ZHINENG_ABC", "WEIRUAN_SHUANGPIN", "PINYIN_JIAJIA", "XIAOHE_SHUANGPIN",
               "ZIGUANG_SHUANGPIN", "ZIRANMA", "SOUGOU_SHUANGPIN", "CLOSE"):
    assert "->SHUANGPIN_" + scheme + ":" in initialize, scheme
scheme_types = set(re.findall(r"L([^;]+);->valueOf\(Ljava/lang/String;\)L\1;", initialize))
assert len(scheme_types) == 1, scheme_types
scheme_paths = list(root.glob(f"smali*/{next(iter(scheme_types))}.smali"))
assert len(scheme_paths) == 1, scheme_paths
scheme_enum = scheme_paths[0].read_text(encoding="utf-8")
for scheme in ("ZHINENG_ABC", "WEIRUAN", "PINYIN_JIAJIA", "XIAOHE", "ZIGUANG", "ZIRANMA", "SOUGOU"):
    assert f'"{scheme}"' in scheme_enum, scheme
engine = path.read_text(encoding="utf-8")
assert re.search(r"invoke-virtual[^\n]*->inputKey\(C\)", engine), "native key decoder"
assert "->getDecodeResult(" in engine and "->candWords:" in engine, "native candidates"
assert "->INPUT_TYPE_CN_9:" in initialize
print(f"PASS: T9 initialization, seven native schemes, digit decoding and candidates ({path})")
