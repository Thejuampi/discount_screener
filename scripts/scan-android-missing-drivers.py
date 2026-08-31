"""Scan the on-device Android SP500 cache for missing DCF drivers.

Pulls nothing. Point it at a copied sqlite file:

    python scripts/scan-android-missing-drivers.py \\
        --db .agents/workspace/tmp/android-state/discount_screener_state.sqlite3

Writes docs/diagnostics/sp500-missing-drivers.json and .md
"""
from __future__ import annotations

import argparse
import json
import sqlite3
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "docs" / "diagnostics" / "sp500-missing-drivers.json"
DEFAULT_MD = ROOT / "docs" / "diagnostics" / "sp500-missing-drivers.md"

SEC_FILED_NET_INTEREST = frozenset({"CBRE", "ULTA", "WSM"})
NO_APPROVED_COUPON = {
    "CMG": (
        "expected",
        "Restaurant lease stock. SEC has no approved interest-expense tag. Markets Insider has no parent bonds.",
    ),
    "LULU": (
        "expected",
        "Lease stock. SEC files InterestPaid only. Cash paid is not a coupon. Markets Insider has no parent bonds.",
    ),
    "LEN": (
        "expected",
        "Homebuilder capitalizes interest. SEC files InterestPaidNet. Markets Insider has no parent bonds.",
    ),
}
NO_PAYLOAD = {
    "BK": (
        "engine_fixed_pending_rebuild",
        "Yahoo quoteSummary 404s with a valid crumb. Fetch the quote HTML page on 404 only. Rebuild and refresh.",
    ),
    "SATS": (
        "engine_fixed_pending_rebuild",
        "Batch quote omits SATS. quoteSummary then 404s. Fetch the quote HTML page on 404 only. Rebuild and refresh.",
    ),
    "FISV": (
        "engine_fixed_pending_rebuild",
        "Never attempted. Tail of tracked_symbol after SATS 404. Next refresh uses quoteSummary, then HTML on 404. FISV is the live ticker.",
    ),
    "FOX": (
        "engine_fixed_pending_rebuild",
        "Never attempted. Dual-class FOX is not FOXA. Next refresh uses quoteSummary, then HTML on 404. Do not copy Class A.",
    ),
    "NWS": (
        "engine_fixed_pending_rebuild",
        "Never attempted. Dual-class NWS is not NWSA. Next refresh uses quoteSummary, then HTML on 404. Do not copy Class A.",
    ),
}


def classify(symbol: str, row: dict | None) -> dict:
    if row is None:
        if symbol in NO_PAYLOAD:
            status, note = NO_PAYLOAD[symbol]
            return entry(symbol, "no_payload", status, note)
        return entry(symbol, "no_payload", "open", "No symbol_latest row. The list never stored this ticker.")
    fund = row.get("fundamentals") or {}
    sector = fund.get("sectorName")
    industry = fund.get("industryName")
    dcf = row.get("dcfAnalysis")
    extra = {
        "sector": sector,
        "industry": industry,
        "company": (row.get("snapshot") or {}).get("companyName"),
    }
    if not isinstance(dcf, dict):
        blob = f"{sector or ''} {industry or ''}".lower()
        if "reit" in blob or "real estate" in blob:
            return entry(
                symbol,
                "not_eligible_silent",
                "expected",
                "REIT or real-estate shell. Classification refuses FCFF. Detail has no reason row.",
                extra,
            )
        return entry(symbol, "no_dcf", "open", "No dcfAnalysis object on the latest payload.", extra)

    model = dcf.get("model")
    biz = dcf.get("businessClass")
    base = dcf.get("baseIntrinsicValueCents") or 0
    source = dcf.get("source")
    policy = dcf.get("modelPolicyVersion")
    reasons = dcf.get("reasonCodes") or []
    providers = dcf.get("providerReasons") or []
    extra.update(
        {
            "model": model,
            "businessClass": biz,
            "baseIntrinsicValueCents": base,
            "source": source,
            "modelPolicyVersion": policy,
            "reasonCodes": reasons[:12],
            "totalDebtDollars": fund.get("totalDebtDollars"),
            "totalCashDollars": fund.get("totalCashDollars"),
        }
    )
    codes = [p.get("code") for p in providers if isinstance(p, dict)]
    statuses = [
        (p.get("provider"), p.get("code"), p.get("upstreamStatus"))
        for p in providers
        if isinstance(p, dict)
    ]
    extra["providerStatuses"] = statuses

    formed = base > 0 and any(str(r).startswith("model=") for r in reasons)
    if formed or (model not in (None, "None") and base > 0):
        extra.pop("reasonCodes", None)
        extra.pop("providerReasons", None)
        extra.pop("providerStatuses", None)
        return entry(symbol, "identity_ok", "closed", "Identity is on file.", extra)

    tax_msgs = [s for s in statuses if s[2] and "marginal tax is unavailable" in str(s[2])]
    fcff_msgs = [s for s in statuses if s[2] and "non_positive_normalized_fcff" in str(s[2])]
    debt_msgs = [s for s in statuses if s[2] and "no aligned market yield, spread, or SEC interest/debt" in str(s[2])]
    lender_msgs = [s for s in statuses if s[2] and "lender book missing" in str(s[2])]
    if "LatestFcfNonPositive" in codes:
        return entry(
            symbol,
            "latest_reported_fcf_non_positive",
            "engine_fixed_pending_rebuild",
            "Yahoo latest reported FCF is non-positive. Source selection now lets aligned driver FCFF run. Rebuild.",
            extra,
        )
    if fcff_msgs:
        status = "engine_fixed_pending_rebuild" if symbol == "SNDK" else "open"
        note = (
            "Latest aligned FCFF is positive; policy/37 keeps that year. Rebuild the app."
            if symbol == "SNDK"
            else "SEC window median FCFF is non-positive. Check the latest year before refusing."
        )
        return entry(symbol, "sec_non_positive_normalized_fcff", status, note, extra)
    if tax_msgs and "MissingDriverEvidence" in codes:
        return entry(
            symbol,
            "yahoo_missing_marginal_tax",
            "engine_fixed_pending_rebuild",
            "Yahoo annualMarginalTaxRate is empty. Engine now attaches the US domicile 21% row when country is set. Rebuild and refresh quotes.",
            extra,
        )
    if "MissingDriverEvidence" in codes or "MissingDrivers" in reasons:
        if lender_msgs:
            blob = f"{industry or ''} {sector or ''}".lower()
            captive = any(
                needle in blob
                for needle in (
                    "machinery",
                    "auto manufacturer",
                    "automotive",
                    "tools",
                    "communication equipment",
                    "computer hardware",
                    "farm",
                )
            )
            if not captive:
                return entry(
                    symbol,
                    "mixed_issuer_missing_lender_book",
                    "engine_fixed_pending_rebuild",
                    "Parent industry is not a captive-finance host. Credit Karma, ratings lines, and IT verticals stay on FCFF. Rebuild.",
                    extra,
                )
            if symbol in ("CAT", "PCAR"):
                return entry(
                    symbol,
                    "mixed_issuer_missing_lender_book",
                    "engine_fixed_pending_rebuild",
                    "EFTS entityName finds the finance-sub 10-K (CAT 0000764764, PCAR 0000731288). Rebuild Detail.",
                    extra,
                )
            if symbol in ("HPE", "SNA"):
                return entry(
                    symbol,
                    "mixed_issuer_missing_lender_book",
                    "expected",
                    "No finance-sub 10-K. Parent segment prints revenue/EBIT, not book equity and NI. Do not invent ROE.",
                    extra,
                )
            return entry(
                symbol,
                "mixed_issuer_missing_lender_book",
                "open",
                "Classifier marked a factory-plus-lender split. Lender book is missing.",
                extra,
            )
        if debt_msgs and symbol == "PCAR":
            return entry(
                symbol,
                "mixed_issuer_missing_lender_book",
                "engine_fixed_pending_rebuild",
                "EFTS entityName finds PACCAR Financial Corp CIK 0000731288. Rebuild Detail.",
                extra,
            )
        if debt_msgs:
            debt = fund.get("totalDebtDollars")
            cash = fund.get("totalCashDollars")
            cash_covers = (
                isinstance(debt, (int, float))
                and isinstance(cash, (int, float))
                and cash >= debt
                and debt >= 0
            )
            if cash_covers:
                status = "engine_fixed_pending_rebuild"
                note = "Reported cash covers reported debt. Coupon failure is now not-applicable. Rebuild."
            elif symbol in SEC_FILED_NET_INTEREST:
                status = "engine_fixed_pending_rebuild"
                note = (
                    "Yahoo interest is empty. Detail copies filed SEC net interest onto Yahoo years. "
                    "Rebuild and open Detail."
                )
            elif symbol in NO_APPROVED_COUPON:
                status, note = NO_APPROVED_COUPON[symbol]
            else:
                status = "open"
                note = (
                    "Material net debt and empty Yahoo interest. "
                    "Still refuses until a yield or filed coupon exists."
                )
            return entry(symbol, "yahoo_missing_cost_of_debt", status, note, extra)
        if biz == "FinancialServices" or "insurance" in (industry or "").lower() or "asset management" in (industry or "").lower() or "healthcare plans" in (industry or "").lower():
            if symbol in ("ARES", "BX"):
                return entry(
                    symbol,
                    "financials_missing_book_or_roe",
                    "engine_fixed_pending_rebuild",
                    "Yahoo payout at or above 1 is retention 0. Refresh quotes.",
                    extra,
                )
            if symbol == "CNC":
                return entry(
                    symbol,
                    "financials_missing_book_or_roe",
                    "engine_fixed_pending_rebuild",
                    "Yahoo latest ROE is a loss year. Detail SEC median of recent years stays positive. Open Detail.",
                    extra,
                )
            if symbol == "WRB":
                return entry(
                    symbol,
                    "financials_missing_book_or_roe",
                    "engine_fixed_pending_rebuild",
                    "Yahoo book is empty. SEC files equity and NI. Open Detail.",
                    extra,
                )
            if symbol == "IVZ":
                return entry(
                    symbol,
                    "financials_missing_book_or_roe",
                    "engine_fixed_pending_rebuild",
                    "Yahoo latest ROE is a loss year. Detail SEC drops loss years and keeps the remaining positive ROE. Open Detail.",
                    extra,
                )
            return entry(
                symbol,
                "financials_missing_book_or_roe",
                "open",
                "Residual income refused. Book or ROE is missing.",
                extra,
            )
        return entry(
            symbol,
            "missing_driver_other",
            "open",
            "Engine refused with MissingDriverEvidence. Read providerStatuses.",
            extra,
        )
    if model in (None, "None") and base == 0:
        return entry(symbol, "zero_identity", "open", "Zero identity without a provider reason.", extra)
    return entry(symbol, "other", "open", "Unclassified refuse. Read providerStatuses.", extra)


def entry(symbol: str, cls: str, status: str, note: str, extra: dict | None = None) -> dict:
    row = {
        "symbol": symbol,
        "class": cls,
        "status": status,
        "note": note,
    }
    if extra:
        row.update(extra)
    return row


def render_md(payload: dict) -> str:
    counts = payload["counts"]
    by_class = payload["byClass"]
    lines = [
        "# SP500 missing drivers",
        "",
        f"Scanned {payload['scannedAt']}. Profile tracked {payload['tracked']} names. Latest rows {payload['latest']}.",
        "Source is the Android `discount_screener_state.sqlite3` copy. List path uses Yahoo. SEC runs on Detail open.",
        "",
        "Fix one class at a time. Do not invent numbers. An expected refuse stays expected.",
        "",
        "## Progress",
        "",
        "| Class | Engine | Next |",
        "| --- | --- | --- |",
        "| `yahoo_missing_marginal_tax` | Domicile 21% proxy when country is set | Rebuild the app. Refresh quotes so `country` is on the snapshot. |",
        "| `sec_non_positive_normalized_fcff` | Latest positive FCFF year (policy/37) | Rebuild. Reopen SNDK. |",
        "| `latest_reported_fcf_non_positive` | Driver path stays open when OCF/CapEx/revenue align | Rebuild. |",
        "| `yahoo_missing_cost_of_debt` | Cash covering debt skips a failed coupon. Detail copies filed SEC interest onto Yahoo years | Rebuild. Open CBRE, ULTA, WSM. CMG, LULU, LEN stay refused. |",
        "| `mixed_issuer_missing_lender_book` | Mixed split only when the parent industry hosts a captive. EFTS entityName loads CAT and PCAR finance-sub 10-Ks | Rebuild CAT, PCAR, INTU, MCO, EPAM, CTSH. HPE and SNA stay refused: no sub 10-K, no parent book. |",
        "| `financials_missing_book_or_roe` | Yahoo payout ≥ 1 is retention 0. Detail SEC fills CNC/IVZ median ROE and WRB book | Refresh quotes for ARES, BX. Open Detail for CNC, WRB, IVZ. |",
        "| `no_payload` | quoteSummary 404 scrapes the quote HTML page | Rebuild. Refresh BK, SATS, FISV, FOX, NWS. Do not copy FOXA or NWSA. |",
        "",
        "## Counts",
        "",
        "| Class | Count | Status |",
        "| --- | ---: | --- |",
    ]
    for item in counts:
        lines.append(f"| `{item['class']}` | {item['count']} | {item['statusHint']} |")
    lines.extend(["", "## Queue", ""])
    for cls, rows in by_class.items():
        if cls == "identity_ok":
            continue
        lines.append(f"### `{cls}` ({len(rows)})")
        lines.append("")
        note = rows[0]["note"] if rows else ""
        lines.append(note)
        lines.append("")
        lines.append("| Symbol | Status | Sector | Industry | Source | Detail |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for row in rows:
            detail = ""
            statuses = row.get("providerStatuses") or []
            if statuses:
                bits = []
                for provider, code, upstream in statuses:
                    piece = f"{provider or '?'}:{code}"
                    if upstream:
                        piece += f" ({upstream})"
                    bits.append(piece)
                detail = "; ".join(bits)
            elif row.get("note"):
                detail = row["note"]
            lines.append(
                "| {symbol} | {status} | {sector} | {industry} | {source} | {detail} |".format(
                    symbol=row["symbol"],
                    status=row["status"],
                    sector=row.get("sector") or "",
                    industry=row.get("industry") or "",
                    source=row.get("source") or "",
                    detail=detail.replace("|", "/"),
                )
            )
        lines.append("")
    lines.extend(
        [
            "## How to refresh",
            "",
            "Copy the device DB, then run the scanner:",
            "",
            "```",
            "python scripts/scan-android-missing-drivers.py --db path/to/discount_screener_state.sqlite3",
            "```",
            "",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--json-out", default=str(DEFAULT_JSON))
    parser.add_argument("--md-out", default=str(DEFAULT_MD))
    args = parser.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row
    tracked = [r["symbol"] for r in con.execute("SELECT symbol FROM tracked_symbol ORDER BY position")]
    latest = {
        r["symbol"]: json.loads(r["payload_json"])
        for r in con.execute("SELECT symbol, payload_json FROM symbol_latest")
    }

    rows = [classify(symbol, latest.get(symbol)) for symbol in tracked]
    class_counts = Counter(r["class"] for r in rows)
    status_hints = {
        "identity_ok": "closed",
        "yahoo_missing_marginal_tax": "engine_fixed_pending_rebuild — domicile tax proxy",
        "latest_reported_fcf_non_positive": "engine_fixed_pending_rebuild",
        "yahoo_missing_cost_of_debt": "mixed — net-cash and SEC-interest pending rebuild; CMG/LULU/LEN expected",
        "mixed_issuer_missing_lender_book": "CAT/PCAR and false mixed pending rebuild; HPE/SNA expected refuse",
        "sec_non_positive_normalized_fcff": "open / SNDK pending rebuild",
        "financials_missing_book_or_roe": "ARES/BX payout clamp pending quote refresh; CNC/WRB/IVZ pending Detail SEC",
        "not_eligible_silent": "expected refuse, UI reason missing",
        "no_payload": "engine_fixed_pending_rebuild — quoteSummary 404 HTML recovery",
        "no_dcf": "open",
        "missing_driver_other": "open",
        "zero_identity": "open",
        "other": "open",
    }
    counts = [
        {"class": cls, "count": n, "statusHint": status_hints.get(cls, "open")}
        for cls, n in class_counts.most_common()
    ]
    by_class = defaultdict(list)
    for row in rows:
        by_class[row["class"]].append(row)
    ordered = {cls: by_class[cls] for cls, _ in class_counts.most_common()}

    payload = {
        "scannedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
        "source": "android emulator discount_screener_state.sqlite3",
        "profile": "sp500",
        "tracked": len(tracked),
        "latest": len(latest),
        "engineOnDevice": "business-class-policy/36-ocf-prior-franchise",
        "engineInRepo": "business-class-policy/37-latest-positive-fcff",
        "counts": counts,
        "byClass": ordered,
    }
    json_path = Path(args.json_out)
    md_path = Path(args.md_out)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_md(payload), encoding="utf-8")
    print("tracked", len(tracked), "latest", len(latest))
    for item in counts:
        print(f"{item['count']:4d}  {item['class']}")
    print("wrote", json_path)
    print("wrote", md_path)


if __name__ == "__main__":
    main()
