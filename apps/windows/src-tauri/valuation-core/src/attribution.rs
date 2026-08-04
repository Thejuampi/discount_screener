//! Where a valuation's width came from.
//!
//! A wide interval is only useful if the reader can see what made it wide.
//! "Uncertain" is a shrug; "uncertain because two analysts cover this name and
//! they disagree" is something to act on, and it is a different sentence from
//! "uncertain because the discount rate rests on a beta estimated off thirty
//! monthly returns" (FR-34).
//!
//! Every propagation step records the variance it contributed under a named
//! input, and those contributions travel with the value. Because the delta
//! method is a sum of squared-partial terms, the contributions are additive by
//! construction: they sum to the total variance exactly, with no reconciliation
//! step that could disagree.

/// An input a valuation's uncertainty can be charged to.
///
/// A closed set on purpose. An input that has no variant here cannot silently
/// widen a published interval without appearing in the attribution.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ValuationInput {
    BaseCashFlow,
    BookValue,
    Growth,
    ReturnOnCapital,
    ReturnOnEquity,
    DiscountRate,
    NetDebt,
    DilutedShares,
}

impl ValuationInput {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::BaseCashFlow => "base_cash_flow",
            Self::BookValue => "book_value",
            Self::Growth => "growth",
            Self::ReturnOnCapital => "return_on_capital",
            Self::ReturnOnEquity => "return_on_equity",
            Self::DiscountRate => "discount_rate",
            Self::NetDebt => "net_debt",
            Self::DilutedShares => "diluted_shares",
        }
    }
}

/// The variance one input contributed, in the units of the value it contributed
/// to — so a contribution can be rescaled as the value is transformed.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Contribution {
    input: ValuationInput,
    variance: f64,
}

impl Contribution {
    pub fn new(input: ValuationInput, variance: f64) -> Self {
        Self { input, variance }
    }

    pub fn input(self) -> ValuationInput {
        self.input
    }

    pub fn variance(self) -> f64 {
        self.variance
    }

    /// Carry this contribution through a transformation whose partial
    /// derivative with respect to the value is `partial`.
    pub fn scaled_by(self, partial: f64) -> Self {
        Self {
            input: self.input,
            variance: self.variance * partial * partial,
        }
    }
}

/// Largest-remainder apportionment of shares into basis points.
///
/// Reported shares sum to exactly 10000 whenever the total is positive, and are
/// all zero when it is not. A reader comparing two attributions is comparing
/// shares that add up, which independent rounding would not guarantee.
pub(crate) fn apportion_bps(shares: &[f64], total: f64) -> Vec<i32> {
    if !(total.is_finite() && total > 0.0) {
        return vec![0; shares.len()];
    }
    let exact: Vec<f64> = shares
        .iter()
        .map(|share| share / total * 10_000.0)
        .collect();
    let mut apportioned: Vec<i32> = exact.iter().map(|share| share.floor() as i32).collect();

    let mut remaining = 10_000 - apportioned.iter().sum::<i32>();
    let mut order: Vec<usize> = (0..exact.len()).collect();
    order.sort_by(|left, right| {
        let left_remainder = exact[*left] - exact[*left].floor();
        let right_remainder = exact[*right] - exact[*right].floor();
        right_remainder
            .partial_cmp(&left_remainder)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    for index in order {
        if remaining <= 0 {
            break;
        }
        apportioned[index] += 1;
        remaining -= 1;
    }
    apportioned
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apportioned_shares_sum_to_exactly_one() {
        let apportioned = apportion_bps(&[1.0, 1.0, 1.0], 3.0);
        assert_eq!(apportioned.iter().sum::<i32>(), 10_000);
    }

    #[test]
    fn a_zero_total_apportions_nothing_rather_than_dividing() {
        assert_eq!(apportion_bps(&[0.0, 0.0], 0.0), vec![0, 0]);
    }

    #[test]
    fn scaling_a_contribution_squares_the_partial() {
        let scaled = Contribution::new(ValuationInput::Growth, 4.0).scaled_by(3.0);
        assert_eq!(scaled.variance(), 36.0);
    }
}
