#!/usr/bin/env python3
"""Build the app's offline FM transmitter lists from the German Wikipedia (CC BY-SA 4.0):
Germany, Austria and Switzerland, one file per country.

    python3 tools/gen_fm_list.py [country=wikitext.json ...]     e.g. de=de.json at=at.json

Without arguments it fetches each page's wikitext through the MediaWiki API. Writes
android/app/src/main/assets/fm/<country>.tsv: one transmitter per line,
frequency (10 kHz units) TAB latitude TAB longitude TAB ERP in kW TAB programme name.
Rows without a programme name or coordinates, and placeholders such as "diverse
Veranstaltungsradios", are left out. The app reads every file in assets/fm/.
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

PAGES = {
    "de": "Liste_der_UKW-Hörfunksender_in_Deutschland",
    "at": "Liste_der_UKW-Hörfunksender_in_Österreich",
    "ch": "Liste_der_UKW-Radiosender_in_der_Schweiz",
}
COUNTRY = {"de": "Germany", "at": "Austria", "ch": "Switzerland"}
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/fm"
PLACEHOLDER = re.compile(r"^(div\.|diverse|verschiedene|veranstaltungsradio)", re.I)


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
        if not name or PLACEHOLDER.match(name) or not c or not 8750 <= f <= 10800:
            continue
        try:
            kw = float(clean(cells[5]).replace(",", "."))
        except ValueError:
            kw = 0.1
        yield f, c[0], c[1], kw, name


def fetch(page):
    import time
    import urllib.parse
    url = "https://de.wikipedia.org/w/api.php?action=parse&prop=wikitext&format=json&redirects=1&page=" + urllib.parse.quote(page)
    req = urllib.request.Request(url, headers={"User-Agent": "dabplayplusfm-gen-fm-list/1.1"})
    data = json.load(urllib.request.urlopen(req))
    time.sleep(2)                                  # the API's rate limit
    return data


def main():
    given = dict(a.split("=", 1) for a in sys.argv[1:])
    for cc, page in PAGES.items():
        if given and cc not in given:
            continue
        data = json.load(open(given[cc], encoding="utf-8")) if cc in given else fetch(page)
        rs = sorted(set(rows(data["parse"]["wikitext"]["*"])))
        out = OUT / f"{cc}.tsv"
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "w", encoding="utf-8") as o:
            o.write(f"# FM transmitters in {COUNTRY[cc]}: German Wikipedia, \"{page.replace('_', ' ')}\", CC BY-SA 4.0\n")
            o.write("# https://de.wikipedia.org/wiki/" + page + "\n")
            o.write("# freq (10 kHz)\tlat\tlon\tERP kW\tprogramme\n")
            for f, la, lo, kw, n in rs:
                o.write(f"{f}\t{la:.4f}\t{lo:.4f}\t{kw:g}\t{n.replace(chr(9), ' ')}\n")
        print(f"{cc}: {len(rs)} transmitters -> {out}")


if __name__ == "__main__":
    main()
