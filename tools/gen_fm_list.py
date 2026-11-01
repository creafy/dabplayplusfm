#!/usr/bin/env python3
"""Build the app's offline FM transmitter list (Germany) from the German Wikipedia's
"Liste der UKW-Hörfunksender in Deutschland" (CC BY-SA 4.0).

    python3 tools/gen_fm_list.py [wikitext.json]

Without an argument it fetches the page's wikitext through the MediaWiki API. Writes
android/app/src/main/assets/fm/de.tsv: one transmitter per line,
frequency (10 kHz units) TAB latitude TAB longitude TAB ERP in kW TAB programme name.
Rows without a programme name or coordinates are left out.
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

PAGE = "Liste_der_UKW-Hörfunksender_in_Deutschland"
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/fm/de.tsv"


def clean(t):
    t = re.sub(r"<ref[^>]*/>", "", t)
    t = re.sub(r"<ref.*?</ref>", "", t, flags=re.S)
    t = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", t)
    t = re.sub(r"\{\{[^}]*\}\}", "", t)
    t = re.sub(r"'''?", "", t)
    t = re.sub(r"<[^>]+>", "", t)
    return " ".join(t.split())


def coord(c):
    m = re.findall(r"(\d+)°\s*(\d+)['′]\s*(\d+(?:[.,]\d+)?)?[\"″]?\s*([NSOEW])", c)
    if len(m) != 2:
        return None
    out = []
    for d, mi, s, h in m:
        x = int(d) + int(mi) / 60 + float((s or "0").replace(",", ".")) / 3600
        out.append(-x if h in "SW" else x)
    return out


def rows(wikitext):
    for block in wikitext.split("\n|-"):
        line = " ".join(l for l in block.strip().split("\n") if not l.startswith("!"))
        if not line.startswith("|"):
            continue
        cells = re.split(r"\|\||\n\|", line.lstrip("|"))
        if len(cells) < 6:
            continue
        try:
            f = round(float(clean(cells[0]).replace(",", ".")) * 100)
        except ValueError:
            continue
        name, c = clean(cells[1]), coord(cells[4])
        if not name or not c or not 8750 <= f <= 10800:
            continue
        try:
            kw = float(clean(cells[5]).replace(",", "."))
        except ValueError:
            kw = 0.1
        yield f, c[0], c[1], kw, name


def main():
    if len(sys.argv) > 1:
        data = json.load(open(sys.argv[1], encoding="utf-8"))
    else:
        import urllib.parse
        url = "https://de.wikipedia.org/w/api.php?action=parse&prop=wikitext&format=json&page=" + urllib.parse.quote(PAGE)
        req = urllib.request.Request(url, headers={"User-Agent": "dabplayplusfm-gen-fm-list/1.0"})
        data = json.load(urllib.request.urlopen(req))
    wt = data["parse"]["wikitext"]["*"]
    rs = sorted(set(rows(wt)))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as o:
        o.write(f"# FM transmitters in Germany: German Wikipedia, \"{PAGE.replace('_', ' ')}\", CC BY-SA 4.0\n")
        o.write("# https://de.wikipedia.org/wiki/" + PAGE + "\n")
        o.write("# freq (10 kHz)\tlat\tlon\tERP kW\tprogramme\n")
        for f, la, lo, kw, n in rs:
            o.write(f"{f}\t{la:.4f}\t{lo:.4f}\t{kw:g}\t{n.replace(chr(9), ' ')}\n")
    print(f"{len(rs)} transmitters -> {OUT}")


if __name__ == "__main__":
    main()
