"""Build an isolated DEX fixture with renamed classes/members for the fingerprint probe.

The input is an existing apktool decode. This never rewrites or installs the host.
Run verify-host-fingerprints.ps1 against the printed fixture APK afterwards.
"""
import argparse
import json
import re
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("decoded", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--tool-jar", type=Path, default=Path("C:/Users/limo2/Tools/apktool/apktool.jar"))
args = parser.parse_args()
source = args.decoded.resolve(strict=True)
destination = args.output.resolve()
if destination == source or source in destination.parents:
    raise SystemExit("Choose an output directory outside the input decode.")
destination.mkdir(parents=True, exist_ok=False)
# scripts/ is one level below the project root.
reference_path = Path(__file__).resolve().parents[1] / "app/src/main/resources/xatype/keyboard-abi.json"
reference = json.loads(reference_path.read_text(encoding="utf-8"))
class_map = {}
method_names = set()
field_names = set()
for identifier in reference["rules"]:
    if identifier.startswith("c:"):
        name = identifier[2:]
        if not name.startswith(("com.mi.", "androidx.preference.", "miuix.preference.", "androidx.fragment.")):
            class_map[f"L{name.replace('.', '/')};"] = f"Lxatype/renamed/C{len(class_map):03d};"
    elif identifier.startswith("m:"):
        name = identifier.split("#", 1)[1].split("(", 1)[0]
        if re.fullmatch(r"[A-Za-z][A-Za-z0-9]{0,2}", name):
            method_names.add(name)
    elif identifier.startswith("f:"):
        field_names.add(identifier.split("#", 1)[1])
method_map = {name: "abi_method_" + name for name in method_names}
field_map = {name: "abi_field_" + name for name in field_names}
files = [file for directory in sorted(source.glob("smali*")) for file in directory.rglob("*.smali")]
# Read actual .class descriptors rather than inferring names from paths.
texts = [(file, file.read_text(encoding="utf-8")) for file in files]
def descriptor(text):
    return re.search(r"(?m)^\.class[^\n]*? (L[^\s;]+;)\s*$", text).group(1)


local_classes = {descriptor(text) for _, text in texts}
member_reference = re.compile(r"(L[^;\s]+;)->([\w$<>]+)([:(])")
class_reference = re.compile("|".join(re.escape(name) for name in sorted(class_map, key=len, reverse=True)))
for file, text in texts:
    def member(match):
        owner, name, kind = match.groups()
        mapping = method_map if kind == "(" else field_map
        return owner + "->" + (mapping.get(name, name) if owner in local_classes else name) + kind
    text = member_reference.sub(member, text)
    text = re.sub(r"(?m)^(\.method[^\n]*?\s)([^\s(]+)(\()",
                  lambda match: match[1] + method_map.get(match[2], match[2]) + match[3], text)
    text = re.sub(r"(?m)^(\.field[^\n]*?\s)([\w$]+)(:)",
                  lambda match: match[1] + field_map.get(match[2], match[2]) + match[3], text)
    text = class_reference.sub(lambda match: class_map[match[0]], text)
    dex_directory = file.relative_to(source).parts[0]
    class_descriptor = descriptor(text)
    output = destination / dex_directory / (class_descriptor[1:-1] + ".smali")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")

java = destination / "AssembleFixture.java"
java.write_text('''
import java.nio.file.*;
import java.io.File;
import brut.androlib.smali.SmaliBuilder;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
public class AssembleFixture {
  public static void main(String[] args) throws Exception {
    DexBuilder dex = new DexBuilder(new Opcodes(29));
    SmaliBuilder parser = new SmaliBuilder(29);
    try(java.util.stream.Stream<Path> files = Files.walk(Paths.get(args[0]))) {
      for(Path path:(Iterable<Path>)files.filter(p->p.toString().endsWith(".smali")).sorted()::iterator)
        if(!parser.buildFile(path.toFile(),dex)) throw new IllegalStateException("Assembly failed: "+path);
    }
    dex.writeTo(new FileDataStore(new File(args[1])));
  }
}
''', encoding="utf-8")
subprocess.run(["javac", "-classpath", str(args.tool_jar), "-d", str(destination), str(java)], check=True)
dex_files = []
for index, directory in enumerate(sorted(destination.glob("smali*"))):
    dex = destination / ("classes.dex" if index == 0 else f"classes{index+1}.dex")
    subprocess.run(["java", "-Xmx1536m", "-classpath", str(destination)+";"+str(args.tool_jar),
                    "AssembleFixture", str(directory), str(dex)], check=True)
    dex_files.append(dex)
apk = destination / "renamed-host.apk"
with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_STORED) as archive:
    for dex in dex_files:
        archive.write(dex, dex.name)
(destination / "renaming.json").write_text(json.dumps({"classes": class_map, "methods": method_map,
    "fields": field_map}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Fixture: {apk}")
print(f"Renamed {len(class_map)} classes, {len(method_map)} method names and {len(field_map)} field names across {len(files)} files")
