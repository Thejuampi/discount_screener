"""Capture anonymous Yahoo API evidence. Never run this script from automated tests."""

import argparse
import gzip
import hashlib
import html
import http.cookiejar
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request


SYMBOLS = ["AAPL", "MSFT", "JPM", "BRK-B", "TSM", "SPY"]
BASE = "https://query1.finance.yahoo.com"
BASELINE_MODULES = (
    "price,financialData,summaryDetail,defaultKeyStatistics,assetProfile,"
    "recommendationTrend,calendarEvents,earningsTrend"
)
COMPACT_MODULES = (
    "price,financialData,summaryDetail,defaultKeyStatistics,summaryProfile,"
    "recommendationTrend,calendarEvents"
)
QUOTE_FIELDS = (
    "symbol,longName,shortName,regularMarketPrice,epsTrailingTwelveMonths,"
    "earningsTimestamp,earningsTimestampStart,earningsTimestampEnd"
)


class Probe:
    def __init__(self, output):
        self.output = output
        output.mkdir(parents=True, exist_ok=True)
        self.records = []
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.http.addheaders = [
            ("User-Agent", "Mozilla/5.0"),
            ("Accept-Language", "en-US,en;q=0.9"),
            ("Accept-Encoding", "gzip"),
        ]

    def get(self, name, url, params=None, save=True):
        public_params = {k: v for k, v in (params or {}).items() if k != "crumb"}
        public_url = url + ("?" + urllib.parse.urlencode(public_params) if public_params else "")
        if params:
            url += "?" + urllib.parse.urlencode(params)
        started = time.perf_counter()
        try:
            response = self.http.open(url, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            wire = response.read()
            encoding = response.headers.get("Content-Encoding", "identity")
            body = gzip.decompress(wire) if encoding == "gzip" else wire
            status = response.code
        if status == 429:
            raise SystemExit("Yahoo returned HTTP 429. Stop requests and retry later.")
        if save:
            (self.output / (name + ".json")).write_bytes(body)
            record = {
                "name": name, "url": public_url, "status": status,
                "wire_bytes": len(wire), "decoded_bytes": len(body), "encoding": encoding,
                "elapsed_ms": round((time.perf_counter() - started) * 1000),
                "sha256": hashlib.sha256(body).hexdigest(),
            }
            self.records.append(record)
            print(json.dumps(record), flush=True)
        return status, body

    def run(self):
        # Inspect the public page without a signed-in browser or private portfolio.
        status, page = self.get("website", "https://finance.yahoo.com/quote/AAPL/", save=False)
        if status != 200:
            raise SystemExit(f"Yahoo page returned HTTP {status}")
        urls = sorted(set(html.unescape(url) for url in re.findall(
            r'https://query[12]\.finance\.yahoo\.com/[^"<>\\\s]+', page.decode("utf-8")
        )))
        urls = [url for url in urls if "crumb=" not in url and "token=" not in url]
        evidence = {"page": "https://finance.yahoo.com/quote/AAPL/", "request_urls": urls}
        (self.output / "website-requests.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")

        # The cookie endpoint can set an anonymous cookie with its HTTP 404 response.
        # Keep all session cookies and the crumb in memory. Never save them as evidence.
        self.get("cookie", "https://fc.yahoo.com/", save=False)
        status, body = self.get("crumb", "https://query2.finance.yahoo.com/v1/test/getcrumb", save=False)
        crumb = body.decode("utf-8").strip()
        if status != 200 or not crumb or len(crumb) > 80 or crumb.startswith(("{", "<")):
            raise SystemExit(f"Anonymous Yahoo session failed: HTTP {status}")

        symbols = ",".join(SYMBOLS)
        self.get("quotes-default", BASE + "/v7/finance/quote", {"symbols": symbols, "crumb": crumb})
        self.get("quotes-fields", BASE + "/v7/finance/quote", {
            "symbols": symbols, "fields": QUOTE_FIELDS, "formatted": "false", "crumb": crumb,
        })
        self.get("quotes-fundamental-fields", BASE + "/v7/finance/quote", {
            "symbols": symbols, "formatted": "false", "crumb": crumb,
            "fields": "regularMarketPrice,targetMeanPrice,targetMedianPrice,numberOfAnalystOpinions,"
                      "recommendationMean,sector,sectorKey,industry",
        })
        for symbol in SYMBOLS:
            url = BASE + "/v10/finance/quoteSummary/" + symbol
            self.get("summary-original-" + symbol, url, {"modules": BASELINE_MODULES, "crumb": crumb})
            self.get("summary-slim-" + symbol, url, {
                "modules": COMPACT_MODULES, "formatted": "false", "crumb": crumb,
            })
        self.get("spark-ohlcv", BASE + "/v7/finance/spark", {
            "symbols": symbols, "range": "1y", "interval": "1wk",
            "indicators": "open,high,low,close,volume", "includeTimestamps": "true",
            "includePrePost": "false",
        })
        self.get("summary-multi", BASE + "/v10/finance/quoteSummary/" + symbols, {
            "modules": "price,financialData", "crumb": crumb,
        })
        for name, symbol in [("single", "AAPL"), ("multi", symbols)]:
            self.get("timeseries-" + name, BASE + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + symbol, {
                "type": "annualFreeCashFlow,annualTotalRevenue", "period1": 1600000000,
                "period2": int(time.time()), "crumb": crumb,
            })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Allow bounded requests to Yahoo")
    parser.add_argument("--out", required=True, type=Path, help="Local evidence directory outside tracked source")
    args = parser.parse_args()
    if not args.live:
        parser.error("Live provider research requires --live")
    probe = Probe(args.out)
    try:
        probe.run()
    finally:
        (args.out / "metrics.json").write_text(json.dumps(probe.records, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
