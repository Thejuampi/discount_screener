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
                 sAdj=r.get("s_adj"), m=r.get("m"), ruleVersion=r.get("rule_version"))
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
                 sAdj=r.get("s_adj"), m=r.get("m"))
            for r in rows]

# V3.1 floats (S_adj, m) are compared with this absolute tolerance; decisions must match exactly.
FLOAT_TOL = 1e-9
FLOAT_FIELDS = ("sAdj", "m")
V31_NAMES = ["LAMBDA_T", "LAMBDA_M", "MOMENTUM_HISTOGRAM_BOUND", "RULE_VERSION"]

def v31_constants():
    """V3.1 constants, Kotlin V31ActAdjustment vs Rust opportunity_v31, read from source."""
    import re
    kt = open(os.path.join(ROOT, "apps/android/core/src/main/kotlin/com/discountscreener/core/engine/V31ActAdjustment.kt")).read()
    rs = open(os.path.join(ROOT, "apps/windows/src-tauri/src/opportunity_v31.rs")).read()
    bad = []
    for n in V31_NAMES:
        k = re.search(rf"const val {n} = (\S+)", kt)
        r = re.search(rf"pub const V31_{n}: [^=]+= ([^;]+);", rs)
        kv, rv = (k.group(1).strip('"') if k else None), (r.group(1).strip('"') if r else None)
        print(f"  {n}: android={kv} headless={rv}" + ("" if kv == rv and kv else "  MISMATCH"))
        if kv != rv or not kv:
            bad.append(n)
    return bad

def diff(kotlin, rust):
    out, gaps = [], 0
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
            if f in ("symbol", "gap") or f not in k:
                continue
            if r.get("gap") and f in ("projectedGapBps", "projectedUpsideBps", "decision") and v is None:
                gaps += 1
                continue
            if f in FLOAT_FIELDS and isinstance(k[f], (int, float)) and isinstance(v, (int, float)):
                if abs(k[f] - v) > FLOAT_TOL:
                    out.append(f"{s}: {f} android={k[f]} headless={v} (tol {FLOAT_TOL})")
                continue
            if k[f] != v:
                out.append(f"{s}: {f} android={k[f]} headless={v}")
        a, b = k.get("projectedUpsideBps"), r.get("projectedUpsideBps")
        if a is not None and b is not None and abs(a - b) / 100 > max_pp:
            max_pp, worst = abs(a - b) / 100, s
    print(f"android={len(kotlin)} headless={len(rust)} common={len(order)} rank_moves={order_moves} "
          f"max_upside_delta_pp={max_pp:.2f} ({worst}) flagged_identity_gaps={gaps} diffs={len(out)}")
    for line in out:
        print("  " + line)
    return out

if __name__ == "__main__":
    print("V3.1 constants:")
    if v31_constants():
        sys.exit(1)
    mode = sys.argv[1]
    kotlin = json.load(open(sys.argv[3] if mode == "replay" else sys.argv[2]))
    rust = rust_rank(sys.argv[2]) if mode == "replay" else from_ranking(sys.argv[3])
    sys.exit(1 if diff(kotlin, rust) and mode == "replay" else 0)
