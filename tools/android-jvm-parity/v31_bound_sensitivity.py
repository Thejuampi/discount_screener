#!/usr/bin/env python3
"""V3.1 m-bound sensitivity. Usage: v31_bound_sensitivity.py DIR, with DIR/{sp500,russell}.{snap,before,after}.json
(snap = opportunity_snapshot.json copy, before/after = rank-snapshot --json of main and V3.1)."""
import json,math,sys
d=sys.argv[1] if len(sys.argv)>1 else "/tmp"
def pct(xs,p):
    xs=sorted(xs);k=(len(xs)-1)*p/100;f=math.floor(k);c=min(f+1,len(xs)-1);return xs[f]+(xs[c]-xs[f])*(k-f)
out=[]
P=lambda s="":out.append(s)
cfgs=[(0.005,0.3),(0.01,0.3),(0.02,0.3),(0.03,0.3),(0.05,0.3),(0.05,0.15)]
P("## |hist/close| percentiles (weekly Year chart, ranked rows = rows the screener scores)\n")
P("| universe | rows | p50 | p75 | p90 | p95 | p99 |\n|---|---|---|---|---|---|---|")
data={}
for u in ("sp500","russell"):
    snap={x["row"]["symbol"]:x.get("year") or {} for x in json.load(open(f"{d}/{u}.snap.json"))["rows"]}
    before=json.load(open(f"{d}/{u}.before.json")); after={r["symbol"]:r for r in json.load(open(f"{d}/{u}.after.json"))}
    x={}
    for r in before:
        y=snap.get(r["symbol"],{});h,c=y.get("histogram_cents"),y.get("latest_close_cents")
        x[r["symbol"]]=(h/c) if (h is not None and c and c>0) else None
    v=[abs(a) for a in x.values() if a is not None]
    P(f"| {u} | {len(v)} | "+" | ".join(f"{pct(v,p)*100:.2f}%" for p in (50,75,90,95,99))+" |")
    data[u]=(before,after,x)
P("\n## Sensitivity (λ_T=1.5; Act = V3 main Act preconditions AND S_adj ≥ 30)\n")
P("| universe | bound | λ_M | rows |m|=1 | Act (V3 main → V3.1) | Act→Watch vs main | Watch→Act vs main |\n|---|---|---|---|---|---|---|")
for u,(before,after,x) in data.items():
    actb={r["symbol"] for r in before if r["decision"]=="Act"}
    for b,lm in cfgs:
        sat=0;n=0;acts=set()
        for r in before:
            a=x[r["symbol"]];m=None if a is None else max(-1,min(1,a/b))
            if m is not None:
                n+=1;sat+=abs(m)==1
            S=r["composite_score"];T=r["technical"] or 0
            sa=S*math.exp(-1.5*max(0,-T)/100)*math.exp(-lm*max(0,-(m or 0)))
            if r["symbol"] in actb and sa>=30: acts.add(r["symbol"])
            if b==0.005 and lm==0.3:
                ar=after[r["symbol"]]
                assert (ar["decision"]=="Act")==(r["symbol"] in acts), r["symbol"]
                assert abs(ar["s_adj"]-sa)<1e-9 and (m is None or abs(ar["m"]-m)<1e-9)
        dn=sorted(actb-acts);up=sorted(acts-actb)
        P(f"| {u} | {b} | {lm} | {sat}/{n} ({100*sat/n:.1f}%) | {len(actb)} → {len(acts)} | {', '.join(dn) or '—'} | {', '.join(up) or '—'} |")
P("\n(0.005/0.3 row reproduced bit-for-bit from the V3.1 binary: decisions, m and S_adj within 1e-9.)")
print("\n".join(out))
