"""Reproduce the bundled data-only Tiger table from a pinned CC0 upstream revision."""
import argparse
import gzip
import hashlib
from pathlib import Path
from urllib.request import urlopen

COMMIT = "3e1bff2f757806152ef5b701c709c24f54cf800f"
SHA256 = "36e16b76d66f4f5be2341396e8eb400e44244d528823a5eabb4e90c9c5c1de24"
BASE = f"https://raw.githubusercontent.com/lvyww/tiger-code/{COMMIT}/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, help="Use an already downloaded tiger.txt and LICENSE")
    args = parser.parse_args()
    data = (args.source_dir / "tiger.txt").read_bytes() if args.source_dir else urlopen(BASE + "tables/tiger.txt", timeout=60).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("Upstream table hash mismatch")
    license_data = (args.source_dir / "LICENSE").read_bytes() if args.source_dir else urlopen(BASE + "LICENSE", timeout=60).read()
    if b"CC0" not in license_data:
        raise ValueError("Unexpected license")
    body = data.decode("utf-8-sig").split("[Data]", 1)[1]
    rows, seen = [], set()
    for line in body.splitlines():
        if not line.strip():
            continue
        code, text = line.split("\t", 1)
        if not (1 <= len(code) <= 4 and code.isascii() and code.isalpha() and code.islower() and text):
            raise ValueError(f"Unsupported upstream row: {line!r}")
        if (code, text) not in seen:
            rows.append(f"{text}\t{code}\n")
            seen.add((code, text))
    target = Path(__file__).resolve().parents[1] / "app/src/main/assets/shape"
    target.mkdir(parents=True, exist_ok=True)
    # AAPT expands *.gz assets and strips the suffix; use an opaque extension.
    with (target / "tiger.table").open("wb") as output:
        with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
            compressed.write("".join(rows).encode("utf-8"))
    (target / "LICENSE-tiger.txt").write_bytes(license_data)
    (target / "SOURCE-tiger.txt").write_text(
        f"Source: https://github.com/lvyww/tiger-code\nRevision: {COMMIT}\n"
        f"Source file: tables/tiger.txt\nSource SHA256: {SHA256}\n"
        f"License: CC0-1.0 (see LICENSE-tiger.txt)\nEntries: {len(rows)}\n"
        "Conversion: retain [Data] rows, deduplicate exact (code, text) pairs, preserve order, text TAB code, UTF-8, gzip mtime=0.\n",
        encoding="utf-8", newline="\n")
    print(f"Generated {len(rows)} entries: {target / 'tiger.table'}")


if __name__ == "__main__":
    main()
