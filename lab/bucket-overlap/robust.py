"""The project's robust centre, in Python, so the lab and production measure the same quantity.

`V4_SPREAD_FULL` is fitted from a spread distribution computed here and then used in Kotlin
against a spread computed there. If the two use different centres, the constant is fitted to one
quantity and gates another, and nothing in the code would say so.

This mirrors `valuation-core::numerics` (`apps/windows/src-tauri/valuation-core/src/numerics.rs`):
median location, MAD scale rescaled to a deviation, one trimming pass at |z| <= 3, and a refusal
rather than a fallback to the untrimmed mean. One pass, deliberately — iterating to a fixed point
lets each removal tighten the scale enough to justify the next, and the estimate becomes a
consequence of the procedure rather than of the evidence.

It refuses instead of returning a number when the sample cannot answer:

  * fewer than three observations — a centre and a spread are two quantities;
  * no width through the middle, which is what a zero MAD means;
  * fewer than three observations left after trimming.
"""

import math

# 1 / Phi^-1(0.75). For a normal sample the MAD converges to 0.6745 sigma, so this makes the
# robust scale read in the same units as the textbook one.
MAD_TO_DEVIATION = 1.482602218505602

# A boundary between populations, not a tuning knob. Do not lower it to make an estimate come out
# somewhere nicer.
MAX_ABSOLUTE_Z = 3.0


class NoCentre(Exception):
    """The sample cannot support a centre. Raised, never quietly replaced by the plain mean."""


def median_of(sample):
    """The middle of a handful, and the centre of an even one is the middle pair's midpoint.

    This is the centre the Kotlin composite uses on its two-to-four bucket scores, because
    `robust_mean` refuses on nearly all of them: below three observations no outlier can be named,
    and four scores with three alike have no width through the middle.
    """
    if not sample:
        return None
    ordered = sorted(sample)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 0:
        return (ordered[middle - 1] + ordered[middle]) / 2.0
    return ordered[middle]


def standardize(sample):
    """The sample expressed in units of its own dispersion, plus that location and scale."""
    if len(sample) < 3:
        raise NoCentre("fewer than three observations")
    if any(not math.isfinite(value) for value in sample):
        raise NoCentre("a non-finite observation would poison the whole sample")
    location = median_of(sample)
    scale = median_of([abs(value - location) for value in sample]) * MAD_TO_DEVIATION
    if not scale > 0.0 or not math.isfinite(scale):
        raise NoCentre("no width through the middle of the sample")
    return location, scale, [(value - location) / scale for value in sample]


def robust_mean(sample):
    """The centre of the sample, computed only from the observations that belong to it."""
    _, _, scores = standardize(sample)
    kept = [value for value, score in zip(sample, scores) if abs(score) <= MAX_ABSOLUTE_Z]
    if len(kept) < 3:
        raise NoCentre("too little left after trimming to speak with")
    return sum(kept) / len(kept)
