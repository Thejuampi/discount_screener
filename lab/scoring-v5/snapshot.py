"""Measure how V5's market bucket changed one captured cross-section.

This is a structural snapshot. It does not measure future returns.

Run from the repository root:

    python lab/scoring-v5/snapshot.py
"""

import csv
import statistics
import sys


DEFAULT_INPUT = "lab/data/score-export-sp500-aggressivev5.csv"


def read_rows(path):
    with open(path, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    required = {"symbol", "qualified", "composite_base", "composite_final"}
    missing = required.difference(rows[0] if rows else set())
    if missing:
        raise ValueError(f"missing columns: {', '.join(sorted(missing))}")
    if len({row["symbol"] for row in rows}) != len(rows):
        raise ValueError("symbols must be unique in one cross-section")
    return rows


def ordinal_ranks(rows, column):
    """Descending ranks with symbol as the deterministic tie break."""
    ordered = sorted(range(len(rows)), key=lambda index: (-int(rows[index][column]), rows[index]["symbol"]))
    ranks = [0] * len(rows)
    for rank, index in enumerate(ordered, start=1):
        ranks[index] = rank
    return ranks


def report(rows, label):
    base = [int(row["composite_base"]) for row in rows]
    final = [int(row["composite_final"]) for row in rows]
    base_ranks = ordinal_ranks(rows, "composite_base")
    final_ranks = ordinal_ranks(rows, "composite_final")
    moves = [abs(before - after) for before, after in zip(base_ranks, final_ranks)]
    deltas = [after - before for before, after in zip(base, final)]

    print(f"[{label}]")
    print(f"rows={len(rows)}")
    print(f"score_raised={sum(delta > 0 for delta in deltas)}")
    print(f"score_lowered={sum(delta < 0 for delta in deltas)}")
    print(f"score_unchanged={sum(delta == 0 for delta in deltas)}")
    print(f"median_score_delta={statistics.median(deltas):g}")
    print(f"median_absolute_rank_move={statistics.median(moves):g}")
    print(f"rank_move_at_least_50={sum(move >= 50 for move in moves)}")
    print(f"rank_move_at_least_100={sum(move >= 100 for move in moves)}")
    print(f"maximum_rank_move={max(moves)}")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_INPUT
    rows = read_rows(path)
    report(rows, "cohort")
    print()
    report([row for row in rows if row["qualified"] == "1"], "qualified")


if __name__ == "__main__":
    main()
