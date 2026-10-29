#!/usr/bin/env python3
"""Field-by-field diff: Android Opps list (driver opps.json, ScreenReplay.project output) vs headless.

  diff_headless.py replay FIXTURE OPPS_JSON   # rank FIXTURE in Rust (dump_fixture_ranking), diff
  diff_headless.py live   OPPS_JSON RANKING   # RANKING = headless /ranking?model=v3&semantics=android JSON

Exit 1 on any difference (replay) / prints a report (live). Compared per row: rank (order),
composite, coverage, fundamentals, technicals, forecast, row/projected confidence, row/projected
gap and upside, trust signal, decision; plus count and max upside delta in percentage points.
"""
import json, os, subprocess, sys, tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
CAP = {"high": "High", "provisional": "Provisional", "low": "Low"}

def rust_rank(fixture):
    fx = json.load(open(fixture))
    for c in fx["symbols"]:  # Rust stores P/E and P/B as u32 (live parser saturates negatives to 0)
        f = c.get("fundamentals") or {}
        for k in ("forwardPeHundredths", "trailingPeHundredths", "priceToBookHundredths"):
            if isinstance(f.get(k), int) and f[k] < 0:
                f[k] = 0
    d = tempfile.mkdtemp()
    fin, fout = os.path.join(d, "in.json"), os.path.join(d, "out.json")
    json.dump(fx, open(fin, "w"))
    subprocess.run(["cargo", "test", "-q", "--no-default-features", "--lib", "dump_fixture_ranking", "--", "--ignored"],
                   cwd=os.path.join(ROOT, "apps/windows/src-tauri"), check=True,
                   env=dict(os.environ, FIXTURE_IN=fin, FIXTURE_OUT=fout), stdout=subprocess.DEVNULL)
    return [dict(symbol=r["symbol"], composite=r["composite_score"], coverage=r["coverage"],
                 fundamentals=r["fundamentals"], technicals=r["technical"], forecast=r["forecast"],
                 rowConfidence=CAP[r["row_confidence"]], projectedConfidence=CAP[r["confidence"]],
                 rowGapBps=r["row_gap_bps"], rowUpsideBps=r["row_upside_bps"],
                 projectedGapBps=r["gap_bps"], projectedUpsideBps=r["upside_bps"],
                 trustSignal=r["trust_signal"], decision=r["decision"], gap=r["projection_gap"],
                 shadow=shadow_camel(r.get("shadow")))
            for r in json.load(open(fout))]

def from_ranking(path):
    rows = json.load(open(path))
    rows = rows.get("rows", rows) if isinstance(rows, dict) else rows
    pct = lambda v: None if v is None else round(v * 100)
    return [dict(symbol=r["symbol"], composite=r["composite_score"], fundamentals=r["fundamentals"],
                 technicals=r["technical"], forecast=r["forecast"],
                 projectedConfidence=CAP.get(r.get("confidence") or "", r.get("confidence")),
                 projectedUpsideBps=pct(r.get("upside_pct")), projectedGapBps=pct(r.get("gap_pct")),
                 trustSignal=r.get("trust_signal"), decision=r.get("decision") or None, gap=r.get("projection_gap"),
                 shadow=shadow_camel(r.get("shadow")))
            for r in rows]

# ---- V3.1 SHADOW (not official output) -------------------------------------------------------
# Official fields are compared exactly, as before. The `shadow` grid is compared per config_id:
# floats (m, sAdj) with absolute tolerance FLOAT_TOL, everything else exactly.
FLOAT_TOL = 1e-9
SHADOW_FLOATS = ("m", "sAdj")
V31_KT = "apps/android/core/src/main/kotlin/com/discountscreener/core/engine/V31ActAdjustment.kt"
V31_RS = "apps/windows/src-tauri/src/opportunity_v31.rs"

def shadow_camel(grid):
    if grid is None:
        return None
    return [dict(configId=g["config_id"], bound=g["bound"], lambdaT=g["lambda_t"], lambdaM=g["lambda_m"],
                 m=g["m"], sAdj=g["s_adj"], shadowDecision=g["shadow_decision"]) for g in grid]

def v31_constants():
    """Shadow constants AND the config list, Kotlin V31ActAdjustment vs Rust opportunity_v31 (source)."""
    import re
    kt, rs = open(os.path.join(ROOT, V31_KT)).read(), open(os.path.join(ROOT, V31_RS)).read()
    bad = []
    for n in ("SHADOW_RULE_VERSION", "LAMBDA_T", "ACT_THRESHOLD"):
        k = re.search(rf"const val {n} = (\S+)", kt)
        r = re.search(rf"pub const V31_{n}: [^=]+= ([^;]+);", rs)
        kv, rv = (k.group(1).strip('"') if k else None), (r.group(1).strip('"') if r else None)
        print(f"  {n}: android={kv} headless={rv}" + ("" if kv == rv and kv else "  MISMATCH"))
        if kv != rv or not kv:
            bad.append(n)
    kc = [(i, float(b), float(l)) for i, b, l in re.findall(r'ShadowConfig\("(\w+)", ([\d.]+), ([\d.]+)\)', kt)]
    rc = [(i, float(b), float(l)) for i, b, l in
          re.findall(r'ShadowConfig\s*\{\s*id:\s*"(\w+)",\s*bound:\s*([\d.]+),\s*lambda_m:\s*([\d.]+),?\s*\}', rs)]
    ok = kc == rc and len(kc) == 6
    print(f"  SHADOW_CONFIGS: android={kc}\n                  headless={rc}" + ("" if ok else "  MISMATCH"))
    if not ok:
        bad.append("SHADOW_CONFIGS")
    return bad, [c[0] for c in kc]

def shadow_diff(sym, ka, ra, ids):
    if ka is None and ra is None:
        return []
    if ka is None or ra is None:
        return [f"{sym}: shadow android={'-' if ka is None else len(ka)} headless={'-' if ra is None else len(ra)}"]
    kb, rb = {g["configId"]: g for g in ka}, {g["configId"]: g for g in ra}
    out = []
    if [g["configId"] for g in ka] != ids or [g["configId"] for g in ra] != ids:
        out.append(f"{sym}: shadow config ids android={list(kb)} headless={list(rb)} want={ids}")
    for cid in ids:
        k, r = kb.get(cid), rb.get(cid)
        if k is None or r is None:
            continue
        for f, kv in k.items():
            rv = r.get(f)
            if f in SHADOW_FLOATS and isinstance(kv, (int, float)) and isinstance(rv, (int, float)):
                if abs(kv - rv) > FLOAT_TOL:
                    out.append(f"{sym}: shadow[{cid}].{f} android={kv} headless={rv} (tol {FLOAT_TOL})")
            elif kv != rv:
                out.append(f"{sym}: shadow[{cid}].{f} android={kv} headless={rv}")
    return out


SHADOW_IDS = []

def diff(kotlin, rust):
    out, gaps, shadow_rows = [], 0, [0]
    if len(kotlin) != len(rust):
        out.append(f"count kotlin={len(kotlin)} headless={len(rust)}")
    ks = {r["symbol"]: (i, r) for i, r in enumerate(kotlin)}
    rs = {r["symbol"]: (i, r) for i, r in enumerate(rust)}
    out += [f"{s}: only in Android" for s in ks if s not in rs] + [f"{s}: only in headless" for s in rs if s not in ks]
    order = [s for s in ks if s in rs]
    order_moves = sum(1 for s in order if ks[s][0] != rs[s][0])
    max_pp, worst = 0.0, None
    for s in order:
        k, r = ks[s][1], rs[s][1]
        if ks[s][0] != rs[s][0]:
            out.append(f"{s}: rank android={ks[s][0]+1} headless={rs[s][0]+1}")
        for f, v in r.items():
            if f in ("symbol", "gap", "shadow") or f not in k:
                continue
            if r.get("gap") and f in ("projectedGapBps", "projectedUpsideBps", "decision") and v is None:
                gaps += 1
                continue
            if k[f] != v:
                out.append(f"{s}: {f} android={k[f]} headless={v}")
        shadow_rows[0] += k.get("shadow") is not None
        out += shadow_diff(s, k.get("shadow"), r.get("shadow"), SHADOW_IDS)
        a, b = k.get("projectedUpsideBps"), r.get("projectedUpsideBps")
        if a is not None and b is not None and abs(a - b) / 100 > max_pp:
            max_pp, worst = abs(a - b) / 100, s
    print(f"android={len(kotlin)} headless={len(rust)} common={len(order)} rank_moves={order_moves} "
          f"max_upside_delta_pp={max_pp:.2f} ({worst}) flagged_identity_gaps={gaps} "
          f"shadow_rows={shadow_rows[0]}x{len(SHADOW_IDS)}cfg diffs={len(out)}")
    for line in out:
        print("  " + line)
    return out

if __name__ == "__main__":
    print("V3.1 shadow constants:")
    bad, SHADOW_IDS[:] = v31_constants()
    if bad:
        sys.exit(1)
    mode = sys.argv[1]
    kotlin = json.load(open(sys.argv[3] if mode == "replay" else sys.argv[2]))
    rust = rust_rank(sys.argv[2]) if mode == "replay" else from_ranking(sys.argv[3])
    sys.exit(1 if diff(kotlin, rust) and mode == "replay" else 0)
