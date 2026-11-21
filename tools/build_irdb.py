#!/usr/bin/env python3
"""Build the compact code database bundled in the Android app.

Input : a checkout of https://github.com/Lucaslhm/Flipper-IRDB (CC0 1.0).
Output: app/src/main/assets/irdb.json

Only the five functions the G20 can carry over IR are kept (power, vol+,
vol-, mute, input). Files of one brand that share the same signals are merged
into one "code set" and sets are ranked by how many model files use them, so
the guided test tries the most common codes first.

usage: build_irdb.py <Flipper-IRDB dir> <out.json>
"""
import json
import os
import re
import sys

CATEGORIES = {
    "TVs": "TV",
    "Projectors": "Projector",
    "Monitors": "Monitor",
    "SoundBars": "Soundbar",
    "Audio_and_Video_Receivers": "AV receiver",
    "Speakers": "Speaker",
}

# normalized function name -> (key, priority). Lower priority = preferred.
NAMES = {
    "power": [
        "power", "pwr", "powertoggle", "onoff", "poweronoff", "standby",
        "powerbutton", "powertv", "tvpower", "power1", "pwrtoggle",
        "poweron", "on", "poweroff", "off",
    ],
    "volup": [
        "volup", "volumeup", "volplus", "volumeplus", "vup", "volup1",
        "tvvolup", "volupup", "volu", "vol", "volumeupplus",
    ],
    "voldn": [
        "voldn", "voldown", "volumedown", "volminus", "volumeminus", "vdown",
        "vdn", "voldn1", "tvvoldn", "vold", "voldwn", "volumedn",
    ],
    "mute": ["mute", "mutetoggle", "muting", "volmute", "audiomute", "tvmute", "mute1"],
    "input": [
        "input", "source", "inputs", "inputselect", "src", "tvav", "av",
        "sources", "inputsource", "selectinput", "inputswitch", "avtv",
        "tvinput", "input1", "source1", "extinput", "hdmi", "hdmi1",
    ],
}
LOOKUP = {}
for key, names in NAMES.items():
    for prio, n in enumerate(names):
        LOOKUP.setdefault(n, (key, prio))

PROTOCOLS = {"NEC", "NECext", "NEC42", "NEC42ext", "Samsung32", "RC5", "RC5X",
             "RC6", "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "RCA", "Pioneer"}


def norm(name: str) -> str:
    n = name.strip().lower().replace("+", "plus").replace("-", "minus")
    return re.sub(r"[^a-z0-9]", "", n)


def le_hex(s: str) -> int:
    b = bytes.fromhex(s.replace(" ", ""))
    return int.from_bytes(b, "little")


def parse_ir(path):
    """Yield (name, signal) from a Flipper .ir file. signal is a JSON-able list."""
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return
    cur = {}

    def flush():
        if "name" not in cur:
            return None
        t = cur.get("type")
        if t == "parsed" and cur.get("protocol") in PROTOCOLS:
            try:
                return cur["name"], [cur["protocol"], le_hex(cur["address"]), le_hex(cur["command"])]
            except (KeyError, ValueError):
                return None
        if t == "raw":
            try:
                f = int(float(cur["frequency"]))
                d = round(float(cur.get("duty_cycle", "0.33")) * 100)
                data = [int(x) for x in cur["data"].split()]
            except (KeyError, ValueError):
                return None
            if f <= 0 or len(data) < 4:
                return None
            return cur["name"], ["RAW", f, d, data]
        return None

    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            if line.startswith("#") and cur:
                r = flush()
                if r:
                    yield r
                cur = {}
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        k, v = k.strip().lower(), v.strip()
        if k == "name" and "name" in cur:
            r = flush()
            if r:
                yield r
            cur = {}
        cur[k] = v
    r = flush()
    if r:
        yield r


def main(src, out):
    db = {}
    stats = {"files": 0, "sets": 0}
    for cat_dir, cat_name in CATEGORIES.items():
        root = os.path.join(src, cat_dir)
        if not os.path.isdir(root):
            continue
        brands = {}
        for dirpath, _, files in os.walk(root):
            rel = os.path.relpath(dirpath, root)
            brand = rel.split(os.sep)[0] if rel != "." else "Unsorted"
            brand = brand.replace("_", " ").strip()
            for fn in sorted(files):
                if not fn.lower().endswith(".ir"):
                    continue
                stats["files"] += 1
                per_key = {}
                for name, sig in parse_ir(os.path.join(dirpath, fn)):
                    hit = LOOKUP.get(norm(name))
                    if not hit:
                        continue
                    key, prio = hit
                    lst = per_key.setdefault(key, [])
                    if all(s != sig for _, s in lst):
                        lst.append((prio, sig))
                if not per_key or not ({"power", "volup", "mute"} & per_key.keys()):
                    continue
                keys = {k: [s for _, s in sorted(v, key=lambda x: x[0])][:4] for k, v in per_key.items()}
                model = os.path.splitext(fn)[0].replace("_", " ")
                # identical first choices = same code set; merge the alternates
                fp = json.dumps({k: v[0] for k, v in keys.items()}, sort_keys=True)
                b = brands.setdefault(brand, {})
                if fp in b:
                    b[fp]["m"].append(model)
                    for k, alts in keys.items():
                        have = b[fp]["k"][k]
                        for s in alts:
                            if s not in have and len(have) < 4:
                                have.append(s)
                else:
                    b[fp] = {"m": [model], "k": keys}
        cat = {}
        for brand, sets in sorted(brands.items(), key=lambda x: x[0].lower()):
            ranked = sorted(sets.values(), key=lambda s: (-len(s["m"]), -len(s["k"])))
            for s in ranked:
                s["m"] = sorted(s["m"])[:12]
            cat[brand] = ranked
            stats["sets"] += len(ranked)
        db[cat_name] = cat
    meta = {
        "source": "Flipper-IRDB (https://github.com/Lucaslhm/Flipper-IRDB), CC0 1.0",
        "files": stats["files"],
        "sets": stats["sets"],
    }
    with open(out, "w") as f:
        json.dump({"meta": meta, "db": db}, f, separators=(",", ":"))
    print(json.dumps(meta), os.path.getsize(out), "bytes", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
