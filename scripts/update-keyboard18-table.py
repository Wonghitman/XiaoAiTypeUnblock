"""Build the offline 18-key prototype dictionary from a pinned Apache-2.0 source."""
import argparse
import gzip
import hashlib
from pathlib import Path
import re
from urllib.request import urlopen

REVISION = "0c6861ef7420ee780270ca6d993d18d4101049d0"
SHA256 = "e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b"
BASE = f"https://raw.githubusercontent.com/rime/rime-pinyin-simp/{REVISION}/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    args = parser.parse_args()
    def read(name):
        return (args.source_dir / name).read_bytes() if args.source_dir else urlopen(BASE + name, timeout=30).read()
    data = read("pinyin_simp.dict.yaml")
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("Dictionary revision/hash mismatch")
    license_data = read("LICENSE")
    if b"Apache License" not in license_data:
        raise ValueError("Unexpected dictionary license")
    entries = {}
    for line in data.decode("utf-8-sig").split("...", 1)[1].splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        text, spelling, weight = parts[:3]
        if not text or not re.fullmatch(r"[a-z]+(?: [a-z]+)*", spelling) or len(spelling.replace(" ", "")) > 48:
            continue
        entries[text, spelling] = max(entries.get((text, spelling), 0), int(weight))
    rows = sorted(((weight, text, spelling) for (text, spelling), weight in entries.items()),
                  key=lambda row: (-row[0], row[2], row[1]))
    target = Path(__file__).resolve().parents[1] / "app/src/main/assets/keyboard18"
    target.mkdir(parents=True, exist_ok=True)
    body = "".join(f"{text}\t{spelling}\t{weight}\n" for weight, text, spelling in rows).encode("utf-8")
    with (target / "pinyin.table").open("wb") as output:
        with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
            compressed.write(body)
    (target / "LICENSE-pinyin.txt").write_bytes(license_data)
    (target / "SOURCE-pinyin.txt").write_text(
        f"Source: https://github.com/rime/rime-pinyin-simp\nRevision: {REVISION}\n"
        f"Source file: pinyin_simp.dict.yaml\nSource SHA256: {SHA256}\n"
        f"License: Apache-2.0 (see LICENSE-pinyin.txt)\nEntries: {len(rows)}\n"
        "Derived from the Android Open Source Project PinyinIME dictionary.\n"
        "Modified for this prototype: extract data rows; deduplicate text/spelling pairs; "
        "sort by weight and retain original spaced pinyin; UTF-8 TSV, deterministic gzip.\n"
        "This application uses its own Kotlin decoder; it does not bundle the Rime engine.\n",
        encoding="utf-8", newline="\n")
    print(f"Packaged {len(rows)} dictionary entries: {target / 'pinyin.table'}")


if __name__ == "__main__":
    main()
