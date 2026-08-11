"""How much do the four opportunity buckets repeat each other?

Wave 0 of the Aggressive V4 plan is a gate, and this is the instrument behind it. Reading the
source says the buckets share inputs: `RegimeFit.valueScore` reads the same three multiples the
fundamentals bucket reads, and `RegimeFit.trendScore` reads the same EMAs the technicals bucket
reads. That the inputs are shared is a fact of the code. How far it carries into the *scores* is a
measurement, and this file takes it.

Population: the whole scored cohort, not the Opportunities list. Qualification keeps roughly one
symbol in eight, and a correlation over the survivors is range-restricted — it reports a smaller
overlap than the engine really has. The `qualified` column lets the attenuation be shown instead
of inherited, so both populations are reported side by side.

Spearman, not Pearson: the buckets are clamped at +/-100 and the tails are flat.

    python lab/bucket-overlap/overlap.py lab/data/score-export-sp500-aggressivev3.csv
"""

import csv
import math
import sys

import numpy as np

BUCKETS = [
    ("F", "fundamentals"),
    ("T", "technical"),
    ("Fc", "forecast"),
    ("M", "market"),
]


def read_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def number(row, column):
    """A blank cell is a missing value, never a zero — zero is a score a bucket can really have."""
    text = row.get(column, "")
    return float(text) if text not in ("", None) else None


def average_ranks(values):
    """Ranks with ties averaged. Ties matter here: the buckets clamp, so they tie at the ends."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    start = 0
    while start < len(order):
        stop = start
        while stop + 1 < len(order) and values[order[stop + 1]] == values[order[start]]:
            stop += 1
        shared = (start + stop) / 2.0 + 1.0
        for position in range(start, stop + 1):
            ranks[order[position]] = shared
        start = stop + 1
    return ranks


def spearman(left, right):
    """Spearman rho over the pairs where both values are present, plus that pair count."""
    pairs = [(a, b) for a, b in zip(left, right) if a is not None and b is not None]
    if len(pairs) < 3:
        return None, len(pairs)
    left_ranks = average_ranks([a for a, _ in pairs])
    right_ranks = average_ranks([b for _, b in pairs])
    return pearson(left_ranks, right_ranks), len(pairs)


def pearson(left, right):
    left_array = np.asarray(left, dtype=float)
    right_array = np.asarray(right, dtype=float)
    left_centred = left_array - left_array.mean()
    right_centred = right_array - right_array.mean()
    denominator = math.sqrt(float(left_centred @ left_centred) * float(right_centred @ right_centred))
    return float(left_centred @ right_centred) / denominator if denominator else None


def rank_r_squared(target, predictors):
    """Share of the target's rank variance that the predictors' ranks already explain.

    This is the question the plan asks in plainer form: how much of the market bucket is already
    known once the other three are known? An R-squared near 1 means the fourth bucket carries
    almost no new information and the average is one opinion with extra weight.
    """
    complete = [
        index
        for index in range(len(target))
        if target[index] is not None and all(p[index] is not None for p in predictors)
    ]
    if len(complete) < len(predictors) + 2:
        return None, len(complete)
    y = np.asarray(average_ranks([target[i] for i in complete]), dtype=float)
    columns = [average_ranks([p[i] for i in complete]) for p in predictors]
    design = np.column_stack([np.ones(len(complete))] + [np.asarray(c, dtype=float) for c in columns])
    coefficients, *_ = np.linalg.lstsq(design, y, rcond=None)
    residual = y - design @ coefficients
    total = float(((y - y.mean()) ** 2).sum())
    return (1.0 - float(residual @ residual) / total) if total else None, len(complete)


def partial_spearman(left, right, controls):
    """Spearman rho between two series once the controls are removed from both.

    A raw rho says two buckets move together. It does not say *through what*. Removing the shared
    input from both sides answers that: if the rho collapses, the shared input was the channel; if
    it survives, something else is carrying it.
    """
    complete = [
        index
        for index in range(len(left))
        if left[index] is not None
        and right[index] is not None
        and all(c[index] is not None for c in controls)
    ]
    if len(complete) < len(controls) + 3:
        return None, len(complete)
    design = np.column_stack(
        [np.ones(len(complete))]
        + [np.asarray(average_ranks([c[i] for i in complete]), dtype=float) for c in controls]
    )
    residuals = []
    for series in (left, right):
        y = np.asarray(average_ranks([series[i] for i in complete]), dtype=float)
        coefficients, *_ = np.linalg.lstsq(design, y, rcond=None)
        residuals.append(y - design @ coefficients)
    return pearson(residuals[0], residuals[1]), len(complete)


def price_over_ema200(row):
    close = number(row, "close_cents")
    ema200 = number(row, "ema200_cents")
    return close / ema200 if close is not None and ema200 not in (None, 0.0) else None


def show(title, rho, count):
    printable = "n/a" if rho is None else f"{rho:+.3f}"
    print(f"  {title:<34} {printable}   n={count}")


def report(rows, label):
    print(f"\n=== {label}  ({len(rows)} rows) ===")
    columns = {key: [number(r, column) for r in rows] for key, column in BUCKETS}

    print("\nBucket pairs (Spearman rho)")
    for index, (left_key, _) in enumerate(BUCKETS):
        for right_key, _ in BUCKETS[index + 1:]:
            rho, count = spearman(columns[left_key], columns[right_key])
            show(f"rho({left_key}, {right_key})", rho, count)

    print("\nHow much of a bucket the other three already know (rank R^2)")
    for key, _ in BUCKETS:
        others = [columns[other] for other, _ in BUCKETS if other != key]
        r_squared, count = rank_r_squared(columns[key], others)
        show(f"R^2({key} | the other three)", r_squared, count)

    print("\nShared inputs — are they the cause, or is the ranking a coincidence?")
    forward_pe = [number(r, "forward_pe_hundredths") for r in rows]
    ev_ebitda = [number(r, "ev_ebitda_hundredths") for r in rows]
    price_to_book = [number(r, "price_to_book_hundredths") for r in rows]
    extension = [price_over_ema200(r) for r in rows]
    for name, series in (
        ("forward P/E", forward_pe),
        ("EV/EBITDA", ev_ebitda),
        ("P/B", price_to_book),
    ):
        for key in ("F", "M"):
            rho, count = spearman(series, columns[key])
            show(f"rho({name}, {key})", rho, count)
    for key in ("T", "M"):
        rho, count = spearman(extension, columns[key])
        show(f"rho(close/EMA200, {key})", rho, count)

    print("\nIs the shared input the channel? (partial rho, control removed from both sides)")
    rho, count = partial_spearman(columns["T"], columns["M"], [extension])
    show("rho(T, M | close/EMA200)", rho, count)
    rho, count = partial_spearman(
        columns["F"], columns["M"], [forward_pe, ev_ebitda, price_to_book]
    )
    show("rho(F, M | the three multiples)", rho, count)


def report_spread(rows, label):
    """The distribution V4_SPREAD_FULL has to be chosen from.

    V4 pays its coverage bonus for agreement, so it needs a spread at which the bonus reaches zero.
    That constant is a number about this population, not a matter of taste, and a constant chosen
    without seeing the distribution would be taste wearing a number.
    """
    spreads = []
    for row in rows:
        present = [number(row, column) for _, column in BUCKETS]
        present = [value for value in present if value is not None]
        if len(present) < 2:
            continue
        centre = sum(present) / len(present)
        spreads.append(sum(abs(value - centre) for value in present) / len(present))
    spreads.sort()
    print(f"\n=== Bucket spread (mean absolute deviation) — {label}, {len(spreads)} rows ===")
    for percentile in (5, 10, 25, 50, 75, 90, 95, 99):
        index = min(len(spreads) - 1, int(round(percentile / 100.0 * (len(spreads) - 1))))
        print(f"  p{percentile:<3} {spreads[index]:6.1f}")
    print(f"  max  {spreads[-1]:6.1f}")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "lab/data/score-export-sp500-aggressivev3.csv"
    rows = read_rows(path)
    qualified = [r for r in rows if r["qualified"] == "1"]
    report(rows, "Cohort — every scored candidate")
    report(qualified, "Qualified only — the Opportunities list (range-restricted)")
    report_spread(rows, "cohort")
    report_spread(qualified, "qualified only")
    print(
        "\nThe standard error of a Spearman rho on n rows is about 1/sqrt(n-1);"
        f" on the cohort that is about {1.0 / math.sqrt(len(rows) - 1):.3f}."
    )


if __name__ == "__main__":
    main()
