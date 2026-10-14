"""Read-only verification of the 21053 rendering signatures used by this change."""
from pathlib import Path
import re
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--smali-root', type=Path, default=Path('work/xiaoai-21053/apktool/smali'))
host = parser.parse_args().smali_root

def verify(path, expression):
    source = (host / path).read_text(encoding='utf-8')
    assert re.search(expression, source), (path, expression)
    return source

renderer = verify('z9/w5.smali', r'(?m)^\.method public static final a\(Lka/c;Lma/k;Le1/o;FFLz9/l5;FFZZ.*Ls0/p;III\)V$')
signature = re.search(r'(?m)^\.method public static final a\((.*?)\)V$', renderer).group(1)
assert len(re.findall(r'L[^;]+;|[ZBCSIJFD]', signature)) == 30
verify('ma/v.smali', r'(?m)^\.method public static Q\(ILs0/p;\)J$')
verify('ma/v.smali', r'(?m)^\.method public static h0\(Ls0/p;\)J$')
verify('androidx/compose/foundation/layout/a.smali', r'(?m)^\.method public final a\(Le1/o;Le1/g;\)Le1/o;$')
verify('e1/b.smali', r'(?m)^\.field public static final b:Le1/g;$')
verify('e1/b.smali', r'(?m)^\.field public static final c:Le1/g;$')
verify('ka/f.smali', r'const-string [^,]+, "GAP"')
verify('ka/c.smali', r'const-string [^,]+, ", useSpecialBackground="')
assert 'Lma/v;->Q(ILs0/p;)J' in renderer
assert 'Landroidx/compose/foundation/layout/a;->a(Le1/o;Le1/g;)Le1/o;' in renderer
print('PASS: host 21053 letter renderer (30 parameters), typography, alignment, GAP and key background ABI')
