"""Can the overlap instrument report an absence of overlap?

A correlation that has only ever been run on real data cannot be trusted to say "no relation".
This file runs it on series whose answer is known in advance, in both directions: a case that must
come out near one, and a case that must come out near zero. If only the first were checked, a
statistic that always returns a large number would pass.

    python lab/bucket-overlap/check_overlap.py
"""

import random
import sys

from overlap import average_ranks, partial_spearman, rank_r_squared, spearman

TOLERANCE = 0.15


def failures():
    generator = random.Random(20260811)
    straight = list(range(400))
    reversed_series = list(reversed(straight))
    noise = [generator.random() for _ in straight]
    other_noise = [generator.random() for _ in straight]

    yield check("a series against itself is a perfect rank match", spearman(straight, straight)[0], 1.0)
    yield check("a reversed series is a perfect inverse", spearman(straight, reversed_series)[0], -1.0)
    yield check("two independent series show no relation", spearman(noise, other_noise)[0], 0.0)

    ties = [value // 40 for value in straight]
    yield check("a heavily tied series still ranks monotonically", spearman(straight, ties)[0], 1.0)

    missing = [None if index % 3 == 0 else value for index, value in enumerate(straight)]
    yield check("blanks are dropped, not read as zero", spearman(missing, straight)[0], 1.0)

    yield check(
        "R^2 is near one when a predictor determines the target",
        rank_r_squared(straight, [straight, noise])[0],
        1.0,
    )
    yield check(
        "R^2 is near zero when no predictor knows the target",
        rank_r_squared(noise, [other_noise])[0],
        0.0,
    )

    driver = [generator.random() for _ in straight]
    left = [value + 0.05 * generator.random() for value in driver]
    right = [value + 0.05 * generator.random() for value in driver]
    yield check(
        "two series driven by one input correlate",
        spearman(left, right)[0],
        1.0,
    )
    yield check(
        "removing that one input leaves nothing behind",
        partial_spearman(left, right, [driver])[0],
        0.0,
    )

    independent_left = [generator.random() for _ in straight]
    independent_right = [generator.random() for _ in straight]
    yield check(
        "removing an unrelated control does not invent a relation",
        partial_spearman(independent_left, independent_right, [driver])[0],
        0.0,
    )

    yield check("ties are averaged, not broken by position", average_ranks([5, 5, 9])[0], 1.5)


def check(title, measured, expected):
    ok = measured is not None and abs(measured - expected) <= TOLERANCE
    measured_text = "n/a" if measured is None else f"{measured:+.3f}"
    print(f"  [{'ok' if ok else 'FAIL'}]  {title:<52} {measured_text}  expected {expected:+.2f}")
    return not ok


def main():
    print("Instrument check — the statistic must be able to report both presence and absence.\n")
    broken = sum(failures())
    print(f"\n{broken} check(s) failed.")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
