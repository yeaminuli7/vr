#!/usr/bin/env python3
"""uBlock Origin-এর ফিল্টার-তালিকা (EasyList + uAssets) অ্যাপের জন্য কম্পাইল করে।
আউটপুট: assets/adfilter_hosts.txt (সবসময়-ব্লক), adfilter_3p.txt (থার্ড-পার্টি-ব্লক),
adfilter_white.txt (এক্সেপশন), cosmetic_generic.css + cosmetic_youtube.css (এলিমেন্ট-হাইডিং)।"""
import os
import re
import urllib.request

SRC = [
    "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
    "https://easylist.to/easylist/easylist.txt",
]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets")
HOST_RE = re.compile(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

always, third, white = set(), set(), set()
gen_css, yt_css = [], set()
stats = {"net": 0, "eh": 0}

for url in SRC:
    print("[*] নামাচ্ছি:", url)
    rq = urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"})
    data = urllib.request.urlopen(rq, timeout=180).read().decode("utf-8", "replace")
    for line in data.splitlines():
        line = line.strip()
        if not line or line.startswith("!") or line.startswith("["):
            continue
        # ---- element hiding ----
        if "##" in line:
            pre, _, sel = line.partition("##")
            if not sel or "^" in sel or "+js" in sel or sel.startswith("#?#") or "{" in sel:
                continue
            if pre:
                # ডোমেইন-নির্দিষ্ট: শুধু youtube-এরগুলো রাখি
                doms = [d.strip().lower() for d in pre.split(",")]
                if any(d in ("youtube.com", "m.youtube.com", "www.youtube.com") for d in doms):
                    if not any(d.startswith("~") for d in doms):
                        yt_css.add(sel)
                continue
            if sel not in yt_css:
                gen_css.append(sel)
                stats["eh"] += 1
            continue
        if "#@#" in line or "#?#" in line:
            continue
        # ---- network ----
        exc = line.startswith("@@")
        if exc:
            line = line[2:]
        if not line.startswith("||"):
            continue
        opts = ""
        if "$" in line:
            line, _, opts = line.partition("$")
        dom = line[2:].split("^")[0].split("*")[0].split("/")[0].lower()
        if not dom or "." not in dom or not HOST_RE.match(dom):
            continue
        if "domain=" in opts:
            continue  # জটিল শর্ত — বাদ
        if exc:
            white.add(dom)
        elif "third-party" in opts or ",3p" in opts:
            third.add(dom)
        else:
            always.add(dom)
        stats["net"] += 1

os.makedirs(OUT, exist_ok=True)


def dump(name, items):
    with open(os.path.join(OUT, name), "w") as f:
        f.write("\n".join(sorted(items)))
    print(f"  {name}: {len(items)}")


dump("adfilter_hosts.txt", always)
dump("adfilter_3p.txt", third)
dump("adfilter_white.txt", white)

yt_sel = [s for s in yt_css if len(s) < 300]
open(os.path.join(OUT, "cosmetic_youtube.css"), "w").write(",".join(yt_sel))
print("  cosmetic_youtube.css:", len(yt_sel), "সিলেক্টর")

gen = [s for s in gen_css if len(s) < 200][:12000]
open(os.path.join(OUT, "cosmetic_generic.css"), "w").write(",".join(gen))
print("  cosmetic_generic.css:", len(gen), "সিলেক্টর")
print("[✓] মোট নেটওয়ার্ক-রুল:", stats["net"], "| এলিমেন্ট-হাইড:", stats["eh"])
