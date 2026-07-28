#!/usr/bin/env python3
"""Regenerate src/commonMain/resources/mihon-source-bridge.json.

Joins the keiyoushi extension index (which publishes each Mihon source's id and
baseUrl) against Nyora's own parser catalogue, matching on registrable domain
first and source name second. The result maps Mihon source id -> Nyora source id
so a restored Mihon backup binds to the right parser without guessing.

Usage:
    python3 tools/build-source-bridge.py \
        --parsers      /path/to/kotatsu-parsers/src \
        --data-driven  /path/to/nyora-data-driven/catalogue.json

Inputs:
  * keiyoushi index  — fetched from GitHub (repo branch, index.min.json)
  * nyora live list  — fetched from the helper's /sources endpoint
  * parser domains   — extracted from the kotatsu-parsers Kotlin sources
  * data-driven      — nyora-data-driven catalogue.json (STRONGLY recommended):
                       current live domains plus language, which both widens
                       coverage and disambiguates per-language sources

Matching rules:
  * shared hosting domains (blogspot.com, my.id, ...) are never matched on —
    dozens of unrelated sites share them
  * when several Nyora sources serve one domain they are per-language variants,
    so the Mihon source's language picks the right one; if it does not single
    one out we refuse the domain match and fall back to the name

Re-run whenever either catalogue changes; the mapping is a point-in-time
snapshot, not a live lookup.
"""
import argparse, json, os, re, sys, urllib.request

KEIYOUSHI = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
NYORA_SOURCES = "https://api.nyora.xyz/sources"
OUT = "src/commonMain/resources/mihon-source-bridge.json"

MIN_NAME_LEN = 4

# Hosting platforms, not site identities. Dozens of unrelated scanlation sites
# share these, so a domain match on one is meaningless — such sources fall
# through to name matching instead.
SHARED_HOSTS = {
    "blogspot.com", "blogger.com", "wordpress.com", "my.id", "github.io",
    "tumblr.com", "wixsite.com", "weebly.com", "neocities.org", "netlify.app",
    "vercel.app", "pages.dev", "herokuapp.com", "glitch.me", "gitbook.io",
    "webnode.page", "onrender.com", "medium.com",
}
MULTI_TLD = {"co.uk","com.br","co.jp","com.tr","co.kr","com.mx","com.au","co.id","com.ar","co.za","com.cn"}

ANN = re.compile(r'@MangaSourceParser\(\s*"([A-Z0-9_]+)"\s*,\s*"([^"]*)"(?:\s*,\s*"([^"]*)")?')
DOMKEY = re.compile(r'ConfigKey\.Domain\(\s*((?:"[^"]*"\s*,?\s*)+)\)')
STR = re.compile(r'"([^"]+)"')
DOMLIT = re.compile(r'^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$', re.I)


def reg_domain(host):
    if not host:
        return ""
    host = re.sub(r'^https?://', '', host.strip().lower()).split("/")[0].split(":")[0]
    host = re.sub(r'^www\d*\.', '', host)
    parts = host.split(".")
    if len(parts) < 2:
        return host
    if ".".join(parts[-2:]) in MULTI_TLD and len(parts) >= 3:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def norm_name(n):
    n = re.sub(r'\s*\((?:[a-z]{2,3}|all|multi)\)\s*$', '', n.lower())
    return re.sub(r'[^a-z0-9]', '', n)


def load_data_driven(path):
    """id (UPPER) -> {domain, lang, name} from the nyora-data-driven catalogue.

    An independent and more current source of domain truth than the literals
    hardcoded in the parser sources: sites migrate, and this catalogue tracks the
    live domain. Used as an ADDITIONAL alias, never a replacement — the old domain
    still identifies backups taken before a migration.
    """
    if not path or not os.path.exists(path):
        return {}
    data = json.load(open(path, encoding="utf-8"))
    rows = data.get("sources", data) if isinstance(data, dict) else data
    return {
        str(r["id"]).upper(): {
            "domain": r.get("domain", ""),
            "lang": (r.get("lang") or "").lower(),
            "name": r.get("name", ""),
        }
        for r in rows if r.get("id")
    }


def extract_parsers(root):
    """id -> {title, domains} from the kotatsu-parsers Kotlin sources."""
    out = {}
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            text = open(os.path.join(dirpath, fn), encoding="utf-8", errors="ignore").read()
            anns = list(ANN.finditer(text))
            for i, a in enumerate(anns):
                sid, title = a.group(1), a.group(2)
                start = a.start()
                end = anns[i + 1].start() if i + 1 < len(anns) else len(text)
                block = text[start:end]
                domains = []
                for d in DOMKEY.finditer(block):
                    domains += STR.findall(d.group(1))
                if not domains:
                    # domain handed to the base-class constructor instead
                    for lit in STR.findall(block):
                        if DOMLIT.match(lit) and not lit.lower().endswith((".png", ".jpg", ".webp", ".svg")):
                            domains.append(lit)
                            break
                if not domains and len(anns) == 1:
                    for d in DOMKEY.finditer(text):
                        domains += STR.findall(d.group(1))
                out[sid] = {"title": title, "domains": sorted({d.lower() for d in domains})}
    return out


def fetch(url):
    # GitHub raw rejects urllib's default User-Agent with a 403.
    req = urllib.request.Request(url, headers={"User-Agent": "nyora-bridge-builder/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parsers", required=True, help="path to kotatsu-parsers src/")
    ap.add_argument("--data-driven", default="", help="path to nyora-data-driven catalogue.json")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--swift-out", default="",
                    help="also emit the bridge as a Swift source file (nyora-ios has no JVM "
                         "and no synchronized resource group, so it embeds the same JSON)")
    args = ap.parse_args()

    keiyoushi = fetch(KEIYOUSHI)
    live = fetch(NYORA_SOURCES)["sources"]
    parsers = extract_parsers(args.parsers)

    live_by_bare = {s["id"].split(":", 1)[-1]: s for s in live}
    data_driven = load_data_driven(args.data_driven)

    # domain -> [(nyora id, lang)] — a list, because one domain can legitimately
    # be served by several per-language Nyora parsers (Toomics, Shueisha, ...).
    by_domain = {}
    by_name = {}
    for sid in sorted(set(parsers) | set(data_driven)):
        if sid not in live_by_bare:
            continue                       # parser exists upstream but is not shipped
        live_id = live_by_bare[sid]["id"]
        lang = (live_by_bare[sid].get("lang") or "").lower()
        rec = parsers.get(sid, {"title": "", "domains": []})

        domains = set(rec.get("domains", []))
        if sid in data_driven and data_driven[sid]["domain"]:
            # Current live domain, in addition to whatever the parser hardcodes.
            domains.add(data_driven[sid]["domain"])
            if not lang:
                lang = data_driven[sid]["lang"]

        for d in domains:
            key = reg_domain(d)
            if not key or key in SHARED_HOSTS:
                continue
            by_domain.setdefault(key, []).append((live_id, lang))

        names = [rec.get("title", ""), live_by_bare[sid]["name"]]
        if sid in data_driven:
            names.append(data_driven[sid]["name"])
        for nm in names:
            key = norm_name(nm)
            if len(key) >= MIN_NAME_LEN:   # CJK-only names normalise to "" and would collide
                by_name.setdefault(key, live_id)

    def match_domain(domain, lang):
        """Resolve a domain to ONE Nyora source, or None if genuinely ambiguous."""
        candidates = by_domain.get(domain)
        if not candidates:
            return None
        distinct = {c[0] for c in candidates}
        if len(distinct) == 1:
            return candidates[0][0]
        # Several Nyora sources serve this site — almost always per-language
        # variants. Use the language to pick; if that does not single one out,
        # refuse rather than guess, and let name matching decide.
        lang = (lang or "").lower()
        exact = sorted({c[0] for c in candidates if c[1] == lang})
        if len(exact) == 1:
            return exact[0]
        return None

    to_nyora, seen, dom_hits, name_hits, unmatched = {}, set(), 0, 0, []
    reverse = {}   # nyora source id -> [{id, lang, name}]
    for ext in keiyoushi:
        for src in ext.get("sources", []):
            mid = str(src.get("id", ""))
            if not mid or mid in seen:
                continue
            seen.add(mid)
            hit = match_domain(reg_domain(src.get("baseUrl", "")), src.get("lang", ""))
            if hit:
                dom_hits += 1
            else:
                key = norm_name(src.get("name", ""))
                hit = by_name.get(key) if len(key) >= MIN_NAME_LEN else None
                if hit:
                    name_hits += 1
            if hit:
                to_nyora[mid] = hit
                reverse.setdefault(hit, []).append({
                    "id": mid,
                    "lang": src.get("lang", ""),
                    "name": src.get("name", ""),
                })
            else:
                unmatched.append(src.get("name", ""))

    # Deterministic candidate order so regenerating is reproducible: English and
    # multi-language extensions first (the usual best default), then by id.
    def rank(c):
        lang = (c.get("lang") or "").lower()
        return (0 if lang == "en" else 1 if lang in ("all", "multi") else 2, lang, c["id"])
    for k in reverse:
        reverse[k] = sorted(reverse[k], key=rank)

    out = {"version": 2, "toNyora": to_nyora, "toMihon": reverse}
    json.dump(out, open(args.out, "w"), indent=0, sort_keys=True)
    print(f"mihon sources    : {len(seen)}")
    print(f"nyora sources    : {len(live)}")
    print(f"bridged          : {len(to_nyora)} (domain {dom_hits}, name {name_hits})")
    print(f"unmatched        : {len(unmatched)}")
    print(f"nyora -> mihon   : {len(reverse)} nyora sources have a Mihon equivalent")
    print(f"data-driven      : {len(data_driven)} sources loaded"
          f"{' (NOT SUPPLIED — pass --data-driven)' if not data_driven else ''}")
    print(f"written          : {args.out}")

    if args.swift_out:
        # Same bytes, embedded as a Swift literal so iOS cannot drift from JVM.
        # ensure_ascii keeps the file pure ASCII; the \uXXXX escapes it produces
        # are only valid inside a Swift RAW literal (#"""), since a plain literal
        # would try to parse \u as a Swift unicode escape and fail to compile.
        payload = json.dumps(out, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
        assert '"""#' not in payload
        with open(args.swift_out, "w") as fh:
            fh.write(
                "//\n"
                "//  MihonSourceBridgeData.swift\n"
                "//  Nyora\n"
                "//\n"
                "//  GENERATED by nyora-shared/tools/build-source-bridge.py — do not edit.\n"
                "//  Regenerate together with the JVM resource so the two stay identical.\n"
                "//\n\n"
                "enum MihonSourceBridgeData {\n"
                "    static let json = #\"\"\"\n"
                f"{payload}\n"
                "\"\"\"#\n"
                "}\n"
            )
        print(f"swift written    : {args.swift_out}")


if __name__ == "__main__":
    sys.exit(main())
