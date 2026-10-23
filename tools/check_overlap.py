#!/usr/bin/env python3
"""比對懸浮視窗 frame 與畫面上文字矩形的交集面積。

用法：
    python tools/check_overlap.py <uiautomator.xml> <dumpsys_windows.txt> [--min-text 2]

靈感來自 HANDOVER.md §4-4 / §9-4：看畫面肉眼看不出「有沒有蓋到字」，
把 uiautomator dump 的文字矩形與 dumpsys 的 overlay window frame 逐格求交集才可靠。

- 文字矩形：uiautomator XML 裡 text 非空、或 class 是 EditText 的節點
- overlay window：dumpsys window windows 裡 package=com.dsh.overlaytranslator 的 Window{...}
  （排除 activity 自己的 window：那些是 full-screen 且有 appop=NONE）
"""
import re
import sys
import xml.etree.ElementTree as ET

PKG = "com.dsh.overlaytranslator"


def parse_bounds(s):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", s.strip())
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1, y1, x2, y2)


def area(r):
    return max(0, r[2] - r[0]) * max(0, r[3] - r[1])


def intersect(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0
    return (x2 - x1) * (y2 - y1)


def text_rects(xml_path, min_text=2):
    tree = ET.parse(xml_path)
    out = []
    for node in tree.iter("node"):
        cls = node.get("class", "")
        txt = node.get("text", "") or ""
        is_edit = "EditText" in cls
        if not is_edit and len(txt.strip()) < min_text:
            continue
        b = parse_bounds(node.get("bounds", ""))
        if not b or area(b) == 0:
            continue
        out.append((txt.strip() or f"<{cls.split('.')[-1]}>", b))
    # 去掉完全被其它矩形包住的父容器（同一文字會出現兩層）
    dedup = []
    for t, b in out:
        if not any(t == t2 and b == b2 for t2, b2 in dedup):
            dedup.append((t, b))
    return dedup


def overlay_windows(dumpsys_path):
    """回傳 [(window_id, frame, parent_frame)]，只取本 app 的 overlay window。"""
    txt = open(dumpsys_path, encoding="utf-8", errors="replace").read()
    wins = []
    # Window #N Window{hash u0 pkg}:   ... 然後往下找 Frames: / mOwnerUid=
    blocks = re.split(r"\n\s*Window #(\d+) Window\{", txt)
    for i in range(1, len(blocks), 2):
        wid, body = blocks[i], blocks[i + 1]
        if f"package={PKG}" not in body and PKG not in body.split("\n")[0]:
            continue
        appop = re.search(r"appop=(\w+)", body)
        if appop and appop.group(1) == "NONE":
            continue  # activity 自己的全螢幕 window
        fr = re.search(r"Frames: parent=(\[[^\]]*\]\[[^\]]*\]).*?frame=(\[[^\]]*\]\[[^\]]*\])", body)
        if not fr:
            continue
        parent, frame = parse_bounds(fr.group(1)), parse_bounds(fr.group(2))
        if not frame or area(frame) == 0:
            continue
        wins.append((wid, frame, parent))
    return wins


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    xml_path, dump_path = sys.argv[1], sys.argv[2]
    rects = text_rects(xml_path)
    wins = overlay_windows(dump_path)
    print(f"文字矩形 {len(rects)} 個、overlay 視窗 {len(wins)} 個\n")
    bad = 0
    for wid, frame, parent in sorted(wins, key=lambda w: area(w[1]), reverse=True):
        w, h = frame[2] - frame[0], frame[3] - frame[1]
        inset = "有 inset" if parent[:2] != (0, 0) else "全螢幕座標"
        kind = "視窗" if (w > 200 or h > 200) else "圓點"
        print(f"Window #{wid}  {kind}  {w}x{h}  {frame}  [{inset}] parent={parent}")
        hits = []
        for t, b in rects:
            ov = intersect(frame, b)
            if ov:
                hits.append((ov, t, b))
        if hits:
            bad += 1
            for ov, t, b in sorted(hits, reverse=True):
                print(f"    ✗ 蓋到 {ov:>7} px²  {b}  {t[:28]!r}")
        else:
            print("    ✓ 零接觸")
    print(f"\n有蓋到字的視窗：{bad} / {len(wins)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
