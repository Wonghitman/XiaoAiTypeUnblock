"""Build the offline emoji table from CLDR 47, Emoji 16 and the bundled pinyin data.

Usage: python scripts/update-emoji-table.py work/emoji-source
Inputs: zh.xml, zh-derived.xml, emoji-test.txt, LICENSE-Unicode.txt.
"""
import argparse
import gzip
import hashlib
import itertools
import shutil
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Explicit aliases, not a rule that removes 小 or duplicates arbitrary characters.
ALIASES = {
    '猪 小猪 猪猪 猪头': '🐷 🐖 🐽', '科学家': '🧑‍🔬 👨‍🔬 👩‍🔬',
    '男科学家': '👨‍🔬', '女科学家': '👩‍🔬', '狗 小狗 狗狗 汪汪': '🐶 🐕',
    '猫 小猫 猫猫 猫咪 喵喵': '🐱 🐈', '兔子 小兔 兔兔': '🐰 🐇',
    '老虎 小老虎': '🐯 🐅', '猴子 小猴': '🐵 🐒', '熊猫 大熊猫': '🐼',
    '鸡 小鸡 小鸡仔': '🐔 🐤 🐥', '鸭子 小鸭': '🦆', '小牛 牛牛': '🐮 🐄',
    '绵羊 小羊 羊羊': '🐑 🐏', '小马 马儿': '🐴 🐎', '小鱼 鱼儿': '🐟 🐠',
    '乌龟 小龟': '🐢', '医生 大夫': '🧑‍⚕️ 👨‍⚕️ 👩‍⚕️',
    '老师 教师': '🧑‍🏫 👨‍🏫 👩‍🏫', '程序员 码农': '🧑‍💻 👨‍💻 👩‍💻',
    '厨师 大厨': '🧑‍🍳 👨‍🍳 👩‍🍳', '宇航员 航天员': '🧑‍🚀 👨‍🚀 👩‍🚀',
    '消防员': '🧑‍🚒 👨‍🚒 👩‍🚒', '警察 警官': '👮 👮‍♂️ 👮‍♀️',
    '开心 高兴': '😄 😊 😁', '哈哈 笑死 笑哭': '😂 🤣', '伤心 难过': '😢 😭',
    '生气 愤怒': '😠 😡', '无语': '😑 🙄', '害羞': '☺️ 😊', '晚安 睡觉': '😴 💤',
    '谢谢 感谢': '🙏 💐', '点赞 真棒': '👍', '加油': '💪 ✊', '庆祝 恭喜': '🎉 🎊',
    '生日 生日快乐': '🎂 🎉 🎁', '爱心 爱你': '❤️ 🥰', '抱抱 拥抱': '🤗 🫂',
}


def build(source):
    canonical = {}
    for line in (source / 'emoji-test.txt').read_text(encoding='utf-8').splitlines():
        if '; fully-qualified' not in line:
            continue
        emoji = ''.join(chr(int(c, 16)) for c in line.split(';')[0].split())
        if not any(0x1F3FB <= ord(c) <= 0x1F3FF for c in emoji):
            canonical[emoji.replace('\ufe0f', '')] = emoji
    rows = defaultdict(list)
    for name in ('zh.xml', 'zh-derived.xml'):
        annotations = list(ET.parse(source / name).iter('annotation'))
        # Exact names precede the broader keywords, keeping ties in Unicode order.
        for annotation in sorted(annotations, key=lambda a: a.get('type') != 'tts'):
            emoji = canonical.get(annotation.get('cp', '').replace('\ufe0f', ''))
            if emoji is None:
                continue
            for word in (annotation.text or '').split('|'):
                word = word.strip()
                # Single characters (\u662f, \u6709, \u4eba) top most syllables; only explicit aliases keep them.
                if len(word) > 1 and all('\u3400' <= c <= '\u9fff' for c in word):
                    if emoji not in rows[word]:
                        rows[word].append(emoji)
    for words, emojis in ALIASES.items():
        for word in words.split():
            rows[word] = list(dict.fromkeys(emojis.split() + rows[word]))

    pinyin_file = ROOT / 'app/src/main/assets/keyboard18/pinyin.table'
    readings = defaultdict(list)
    with gzip.open(pinyin_file, 'rt', encoding='utf-8') as stream:
        for line in stream:
            word, spelling, weight = line.rstrip().split('\t')
            readings[word].append((int(weight), spelling))
    for values in readings.values():
        values.sort(reverse=True)

    output = []
    for word, emojis in sorted(rows.items()):
        spellings = [s for _, s in readings[word]]
        if not spellings and all(readings[c] for c in word):
            # ponytail: missing phrases use the most frequent character reading;
            # add a phrase reading when a polyphonic name needs correction.
            spellings = [' '.join(readings[c][0][1] for c in word)]
        codes = list(dict.fromkeys(s.replace(' ', "'") for s in spellings))
        if codes:
            output.append(f"{word}\t{' '.join(codes)}\t{' '.join(emojis)}\n")
    table = ROOT / 'app/src/main/resources/xatype/emoji.tsv'
    table.write_text(''.join(output), encoding='utf-8', newline='\n')
    shutil.copyfile(source / 'LICENSE-Unicode.txt', table.with_name('LICENSE-emoji.txt'))
    files = [source / n for n in ('zh.xml', 'zh-derived.xml', 'emoji-test.txt')] + [pinyin_file]
    hashes = '\n'.join(f'{p.name}: {hashlib.sha256(p.read_bytes()).hexdigest()}' for p in files)
    table.with_name('SOURCE-emoji.txt').write_text(
        'Emoji associations: Unicode CLDR 47 / Emoji 16.0 (Unicode-3.0).\n'
        'https://github.com/unicode-org/cldr/tree/release-47/common/annotations\n'
        'https://github.com/unicode-org/cldr/tree/release-47/common/annotationsDerived\n'
        'https://unicode.org/Public/emoji/16.0/emoji-test.txt\n'
        'Pinyin: bundled rime-pinyin-simp / AOSP data (Apache-2.0); see assets/keyboard18/SOURCE-pinyin.txt and LICENSE-pinyin.txt.\n'
        'Modified: invert Chinese names/keywords; normalize emoji presentation; omit skin-tone variants; add explicit aliases and syllable-separated pinyin for full/Shuangpin matching; deterministic UTF-8 TSV.\n'
        'Candidate filtering informed by WindInput (no Rust code or Rime engine bundled).\n'
        'https://github.com/huanfeng/WindInput\n'
        f'Build: python scripts/update-emoji-table.py <source-directory>\nEntries: {len(output)}\n{hashes}\n',
        encoding='utf-8', newline='\n')
    assert any(line.startswith("科学家\tke'xue'jia\t🧑‍🔬 👨‍🔬 👩‍🔬") for line in output)
    assert any(line.startswith("猪猪\tzhu'zhu\t🐷 🐖 🐽") for line in output)
    assert all(e.replace('\ufe0f', '') in canonical for e in itertools.chain.from_iterable(rows.values()))
    print(f'{len(output)} keyword entries -> {table}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    build(parser.parse_args().source)
