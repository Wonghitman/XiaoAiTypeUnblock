"""Generate offline test vectors from pinned upstream Rime schemes, independent of Kotlin mappings."""
import gzip
import hashlib
from pathlib import Path
import re
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RIME = "https://raw.githubusercontent.com/rime/rime-double-pinyin/01a13287cbd27819be1c34fa1ddc1b3643d5001b/"
ICE = "https://raw.githubusercontent.com/iDvel/rime-ice/da1fbe602e38f26db846fa10120ee64c2b0324c0/"
SOURCES = {
    "XIAOHE": RIME + "double_pinyin_flypy.schema.yaml",
    "ZIRANMA": RIME + "double_pinyin.schema.yaml",
    "WEIRUAN": RIME + "double_pinyin_mspy.schema.yaml",
    "SOUGOU": ICE + "double_pinyin_sogou.schema.yaml",
    "ZHINENG_ABC": RIME + "double_pinyin_abc.schema.yaml",
    "PINYIN_JIAJIA": RIME + "double_pinyin_pyjj.schema.yaml",
    "ZIGUANG": ICE + "double_pinyin_ziguang.schema.yaml",
}


def algebra(source, syllable):
    values = {syllable}
    block = source.split("  algebra:", 1)[1].split("\ntranslator:", 1)[0]
    for line in block.splitlines():
        line = line.strip()
        if not re.match(r"- (erase|derive|xform|xlit)/", line):
            continue
        kind, pattern, *rest = line[2:].split("/")
        replacement = rest[0] if rest else ""
        if kind == "erase":
            values = {value for value in values if not re.search(pattern, value)}
        elif kind == "xlit":
            values = {value.translate(str.maketrans(pattern, replacement)) for value in values}
        else:
            replacement = re.sub(r"\$(\d+)", lambda m: r"\g<" + m[1] + ">", replacement)
            changed = {re.sub(pattern, replacement, value) for value in values}
            values = values | changed if kind == "derive" else changed
    return sorted(value for value in values if re.fullmatch("[a-z;]{1,2}", value))


def main():
    syllables = set()
    with gzip.open(ROOT / "app/src/main/assets/keyboard18/pinyin.table", "rt", encoding="utf-8") as source:
        for row in source:
            syllables.update(row.split("\t")[1].split())
    rows, metadata = [], ["Reference key assignments for the independent Kotlin ShuangPin encoder."]
    for scheme, url in SOURCES.items():
        data = urlopen(url, timeout=30).read()
        source = data.decode("utf-8-sig")
        metadata += [f"{scheme}: {url}", f"SHA256: {hashlib.sha256(data).hexdigest()}"]
        for syllable in sorted(syllables):
            codes = algebra(source, syllable)
            rows.append(f"{scheme}\t{syllable}\t{','.join(codes) or '-'}\n")
    target = ROOT / "app/src/test/resources/shuangpin-reference.tsv"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("".join(rows), encoding="utf-8", newline="\n")
    metadata += ["Sources: Rime double-pinyin (GPL-3.0), Rime Ice (GPL-3.0).",
                 "Only standard key-assignment facts and derived test vectors are included; no Rime engine is bundled.",
                 "18-key adaptation: the L group additionally accepts the semicolon final in Microsoft, Sogou and Ziguang.",
                 "Canonical zero initials: Xiaohe/Natural use repeated vowels or the original two-letter spelling; other schemes use O.",
                 "Regenerate test vectors with: python scripts/update-shuangpin-reference.py"]
    (ROOT / "app/src/main/assets/keyboard18/SOURCE-shuangpin.txt").write_text("\n".join(metadata) + "\n", encoding="utf-8", newline="\n")
    print(f"Generated {len(rows)} reference rows for {len(syllables)} shipped syllables across seven schemes.")


if __name__ == "__main__":
    main()
