"""Check the actual APK asset path/encoding, including AAPT's automatic gzip expansion."""
import argparse
import gzip
import re
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        data = apk.read("assets/shape/tiger.table")
        if data[:2] != b"\x1f\x8b":
            raise ValueError("Bundled table must remain gzip encoded for ShapeStore")
        rows = gzip.decompress(data).decode("utf-8").splitlines()
        if len(rows) != 171074 or len(set(rows)) != len(rows):
            raise ValueError("Wrong table size or duplicates")
        for row in rows:
            text, code = row.split("\t")
            if not text or not re.fullmatch("[a-z]{1,4}", code):
                raise ValueError("Invalid bundled row")
        if "你\tjx" not in rows or "好\tbh" not in rows:
            raise ValueError("Missing smoke-test words")
        if b"CC0" not in apk.read("assets/shape/LICENSE-tiger.txt"):
            raise ValueError("Missing license")
    print("PASS: APK contains readable gzip table, 171074 valid unique rows, smoke-test words and license")


if __name__ == "__main__":
    main()
