#!/usr/bin/env python3
"""Generate shared/contracts/opportunity-v3-headless-fixture.json (deterministic).

The fixture holds inputs only. The expected output is produced by the Kotlin
engine (apps/android/core HeadlessV3ParityGoldenTest, run with
HEADLESS_GOLDEN_WRITE=1) into opportunity-v3-headless-golden.json.

Contents:
  FX00..FX47  random rows (gap, profitability, signal age 0..3600 s, missing data)
  FXAA        exact twin of FX12          -> full tie, decided by symbol
  FXW1..FXW3  weightedAnalystCount ?: analystOpinionCount confidence fallback
  FXW4        weighted fair != plain fair and outside [low, high] -> clamped
  FXW5        plain external fair value 0 (Kotlin still treats it as a target)
  FXCA/FXCB   equal composite, different coverage (higher coverage = FXCB, so
              dropping the coverage key reorders them)
  FXKA/FXKB   equal composite+coverage, different confidence (higher = FXKB)

The tie pairs are picked from a seeded candidate pool scored by the Rust
Android-semantics path (`cargo test ... dump_fixture_ranking -- --ignored`);
picking only chooses inputs, Kotlin still produces every expected value.
Requirements: run from the repository root (relative paths) with `cargo` on
PATH (it builds and runs the Rust test crate to score the pool):
    python3 tools/gen_headless_v3_fixture.py
"""
import json, os, random, subprocess, tempfile

rng = random.Random(20261027)
SECTORS = [
    ("Technology", "technology", "Semiconductors", "semiconductors"),
    ("Healthcare", "healthcare", "Medical Devices", "medical-devices"),
    ("Energy", "energy", "Oil & Gas E&P", "oil-gas-e-p"),
    ("Financial Services", "financial-services", "Banks - Regional", "banks-regional"),
    ("Financial Services", "financial-services", "Insurance - Diversified", "insurance-diversified"),
    ("Communication Services", "communication-services", "Advertising Agencies", "advertising-agencies"),
    ("Real Estate", "real-estate", "REIT - Retail", "reit-retail"),
    (None, None, None, None),  # unclassified
    ("Industrials", "industrials", "Specialty Industrial Machinery", "specialty-industrial-machinery"),
    ("Consumer Defensive", "consumer-defensive", "Discount Stores", "discount-stores"),
]

def maybe(p, v):
    return v if rng.random() < p else None

def candles(base, drift, vol):
    out, px, t = [], base, 1729468800
    for i in range(53):
        px = max(100, int(px * (1 + drift + rng.gauss(0, vol))))
        hi = int(px * (1 + abs(rng.gauss(0, vol / 2))))
        lo = int(px * (1 - abs(rng.gauss(0, vol / 2))))
        op = int((hi + lo) / 2)
        out.append(dict(epochSeconds=t + i * 604800, openCents=op, highCents=hi, lowCents=lo,
                        closeCents=px, volume=int(rng.uniform(5e6, 5e7))))
    return out

def make_row(i, sym):
    sec = SECTORS[i % len(SECTORS)]
    price = rng.randint(1500, 60000)
    # gap mix: below / around / above 20%
    gap = rng.choice([0.05, 0.15, 0.199, 0.20, 0.22, 0.3, 0.45, -0.1])
    intrinsic = int(price / (1 - gap)) if gap < 1 else price * 3
    profitable = rng.random() > 0.12
    shares = rng.randint(100_000_000, 3_000_000_000)
    mcap = price * shares // 100
    fcf = maybe(0.75, int(mcap * rng.uniform(-0.03, 0.10)))
    ocf = maybe(0.85, int(abs(fcf or mcap * 0.05) * rng.uniform(0.8, 2.0)))
    f = dict(symbol=sym, sectorName=sec[0], sectorKey=sec[1], industryName=sec[2], industryKey=sec[3],
             marketCapDollars=maybe(0.8, mcap), sharesOutstanding=shares,
             forwardPeHundredths=maybe(0.85, rng.randint(500, 5000)),
             priceToBookHundredths=maybe(0.7, rng.randint(50, 900)),
             returnOnEquityBps=maybe(0.8, rng.randint(-1000, 4000)),
             enterpriseToEbitdaHundredths=maybe(0.7, rng.randint(300, 3000)),
             totalDebtDollars=maybe(0.7, rng.randint(10**8, 10**11)),
             totalCashDollars=maybe(0.7, rng.randint(10**8, 10**11)),
             debtToEquityHundredths=maybe(0.5, rng.randint(0, 400)),
             freeCashFlowDollars=fcf, operatingCashFlowDollars=ocf,
             betaMillis=maybe(0.8, rng.randint(400, 2200)),
             earningsGrowthBps=maybe(0.7, rng.randint(-2000, 4000)))
    if i in (5, 17):  # no fundamentals at all
        f = None
    n = rng.randint(0, 40) if i % 3 else rng.randint(10, 40)
    sb, b, h = rng.randint(0, n), 0, 0
    b = rng.randint(0, n - sb); h = rng.randint(0, n - sb - b)
    ext = None
    if rng.random() > 0.1:
        ext = dict(symbol=sym, fairValueCents=intrinsic, ageSeconds=rng.choice([0, 0, 5, 30, 31, 3600]),
                   lowFairValueCents=int(intrinsic * rng.uniform(0.6, 0.95)),
                   highFairValueCents=int(intrinsic * rng.uniform(1.05, 1.6)),
                   analystOpinionCount=n, recommendationMeanHundredths=rng.randint(120, 380),
                   strongBuyCount=sb, buyCount=b, holdCount=h, sellCount=n - sb - b - h, strongSellCount=0)
    yc = candles(price * rng.uniform(0.7, 1.3), rng.uniform(-0.01, 0.012), rng.uniform(0.02, 0.06)) if i % 9 != 4 else []
    return (dict(snapshot=dict(symbol=sym, companyName=f"Fixture {i}", profitable=profitable,
                                   marketPriceCents=price, intrinsicValueCents=intrinsic),
                     external=ext, fundamentals=f, yearCandles=yc))


rows = [make_row(i, f"FX{i:02d}") for i in range(48)]

# deliberate tie: identical inputs under two symbols (tie-break → symbol asc)
twin = json.loads(json.dumps(rows[12]))  # FX12 is qualified
for k in ("snapshot", "external", "fundamentals"):
    if twin[k]: twin[k]["symbol"] = "FXAA"
rows.append(twin)

def renamed(row, sym):
    row = json.loads(json.dumps(row))
    for k in ("snapshot", "external", "fundamentals"):
        if row[k]:
            row[k]["symbol"] = sym
    return row

# weighted analyst count fallback (FX12 is qualified + Supportive at age 0)
base = renamed(rows[12], "FXW1")
base["external"]["ageSeconds"] = 0
fair = base["external"]["fairValueCents"]
w_ok = dict(weightedFairValueCents=fair, lowFairValueCents=int(fair * 0.8), highFairValueCents=int(fair * 1.2))
for sym, opinions, weighted, wfair in (("FXW1", 2, 5, True), ("FXW2", 5, 2, True), ("FXW3", 2, 5, False)):
    r = renamed(base, sym)
    r["external"].update(analystOpinionCount=opinions, weightedAnalystCount=weighted)
    if wfair:
        r["external"].update(w_ok)
    rows.append(r)
r = renamed(base, "FXW4")  # weighted fair 1.5x plain, clamped to high = 1.2x
r["external"].update(analystOpinionCount=6, weightedAnalystCount=6, weightedFairValueCents=int(fair * 1.5),
                     lowFairValueCents=int(fair * 0.9), highFairValueCents=int(fair * 1.2))
rows.append(r)
r = renamed(base, "FXW5")  # plain fair 0, no weighted
r["external"].update(fairValueCents=0, analystOpinionCount=6)
rows.append(r)

def doc(rows):
    return dict(contract="opportunity-v3-headless-fixture", version=2,
                note="Inputs for Android-vs-headless V3 parity (context off, ChartRange.Year). Generated by tools/gen_headless_v3_fixture.py; never edit by hand.",
                capturedAtEpochSeconds=1761580800, symbols=rows)

def rust_rank(rows):
    d = tempfile.mkdtemp()
    fin, fout = os.path.join(d, "in.json"), os.path.join(d, "out.json")
    json.dump(doc(rows), open(fin, "w"))
    subprocess.run(["cargo", "test", "-q", "--no-default-features", "--lib", "dump_fixture_ranking",
                    "--", "--ignored"], cwd="apps/windows/src-tauri", check=True,
                   env=dict(os.environ, FIXTURE_IN=os.path.abspath(fin), FIXTURE_OUT=os.path.abspath(fout)),
                   stdout=subprocess.DEVNULL)
    return {r["symbol"]: r for r in json.load(open(fout))}

CONF = {"low": 0, "provisional": 1, "high": 2}
rng = random.Random(20261028)
pool = {}
for i in range(600):
    r = make_row(i, f"PL{i:03d}")
    if r["external"]:
        r["external"]["ageSeconds"] = rng.choice([0, 0, 0, 31])
    if i % 4 == 0:
        r["yearCandles"] = []  # drop technicals -> lower coverage
    pool[f"PL{i:03d}"] = r
scored = rust_rank(list(pool.values()))
ranked = sorted(scored.values(), key=lambda r: r["symbol"])

def find(pred):
    for a in ranked:
        for b in ranked:
            if a["symbol"] < b["symbol"] and a["composite_score"] == b["composite_score"]:
                for hi, lo in ((a, b), (b, a)):
                    if pred(hi, lo):
                        return hi, lo
    raise SystemExit("no candidate pair; enlarge the pool")

# coverage: hi has more coverage, and would NOT win on confidence or upside
hi, lo = find(lambda h, l: h["coverage"] > l["coverage"]
              and (CONF[h["confidence"]], h["upside_bps"]) < (CONF[l["confidence"]], l["upside_bps"]))
rows += [renamed(pool[lo["symbol"]], "FXCA"), renamed(pool[hi["symbol"]], "FXCB")]
# confidence: same coverage, hi more confident but lower upside
hi, lo = find(lambda h, l: h["coverage"] == l["coverage"]
              and CONF[h["confidence"]] > CONF[l["confidence"]] and h["upside_bps"] < l["upside_bps"])
rows += [renamed(pool[lo["symbol"]], "FXKA"), renamed(pool[hi["symbol"]], "FXKB")]

json.dump(doc(rows),
          open("shared/contracts/opportunity-v3-headless-fixture.json", "w"), indent=1)
print(len(rows))
