//! The evidence intake contract.
//!
//! Every quantity entering the Core arrives as an [`Observation`]: a value, the
//! uncertainty that was *measured* alongside it, and the provenance that says
//! where it came from. There is no fourth shape, and in particular there is no
//! bare number.
//!
//! The single most expensive defect in the previous engine was that missing
//! evidence was representable as zero. A contaminated growth history read as
//! "this company will never grow again"; an unavailable analyst forecast read as
//! "the analysts forecast nothing". Both are type errors, and they are
//! unrepresentable here: [`Observation::Absent`] carries a reason and cannot be
//! read as a number without the caller handling the absence.

/// Why a quantity could not be measured.
///
/// Absence is a first-class outcome with a cause attached — never a zero, never
/// an empty string, never a sentinel numeric. (FR-7)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AbsenceReason {
    /// The provider does not carry this field for this issuer.
    NotReported,
    /// The periods exist but are not usable as evidence of the underlying
    /// process — an acquisition-contaminated growth year, a restated basis.
    ContaminatedPeriod,
    /// Fewer usable periods than the statistic requires.
    InsufficientObservations,
    /// The provider was reachable but declined or failed for this field.
    ProviderUnavailable,
    /// The value arrived but fell outside the range the policy admits.
    OutOfPolicyRange,
}

impl AbsenceReason {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::NotReported => "not_reported",
            Self::ContaminatedPeriod => "contaminated_period",
            Self::InsufficientObservations => "insufficient_observations",
            Self::ProviderUnavailable => "provider_unavailable",
            Self::OutOfPolicyRange => "out_of_policy_range",
        }
    }
}

/// How an uncertainty was arrived at.
///
/// FR-8 requires uncertainty to be *measured*, not assumed. Carrying the basis
/// in the type is what makes an assumed variance impossible to smuggle in: there
/// is no variant meaning "a number someone picked".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UncertaintyBasis {
    /// Standard error of the mean over `n` realized periods.
    SampleVariance { observations: u32 },
    /// Dispersion across `n` contributing analysts.
    AnalystDispersion { analysts: u32 },
}

impl UncertaintyBasis {
    pub fn sample_size(self) -> u32 {
        match self {
            Self::SampleVariance { observations } => observations,
            Self::AnalystDispersion { analysts } => analysts,
        }
    }
}

/// A measured variance together with the basis that produced it.
///
/// Construction is fallible on purpose: a non-positive or non-finite variance is
/// not a small error to be clamped away, it is evidence that the caller does not
/// have the uncertainty it claims to have.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Uncertainty {
    variance: f64,
    basis: UncertaintyBasis,
}

impl Uncertainty {
    /// Returns `None` when the variance is not a usable positive real, making an
    /// invalid uncertainty unrepresentable rather than merely rejected later.
    pub fn from_variance(variance: f64, basis: UncertaintyBasis) -> Option<Self> {
        (variance.is_finite() && variance > 0.0).then_some(Self { variance, basis })
    }

    pub fn variance(self) -> f64 {
        self.variance
    }

    pub fn basis(self) -> UncertaintyBasis {
        self.basis
    }

    /// Inverse-variance weight. Always finite and positive by construction.
    pub fn precision(self) -> f64 {
        1.0 / self.variance
    }
}

/// Where a value came from, and when it was seen.
///
/// FR-10. The Core never reads a clock, so `observed_epoch_day` is evidence
/// supplied by the Shell like any other input.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Provenance {
    source: String,
    observed_epoch_day: i64,
}

impl Provenance {
    pub fn new(source: impl Into<String>, observed_epoch_day: i64) -> Self {
        Self {
            source: source.into(),
            observed_epoch_day,
        }
    }

    pub fn source(&self) -> &str {
        &self.source
    }

    pub fn observed_epoch_day(&self) -> i64 {
        self.observed_epoch_day
    }
}

/// A value, its measured uncertainty, and its provenance — or a labelled absence.
///
/// Deliberately has no `Default` and no constructor taking a bare value (FR-6).
/// The only way to obtain a `Measured` is to supply all three parts.
#[derive(Debug, Clone, PartialEq)]
pub enum Observation<T> {
    Measured {
        value: T,
        uncertainty: Uncertainty,
        provenance: Provenance,
    },
    Absent {
        reason: AbsenceReason,
        provenance: Provenance,
    },
}

impl<T> Observation<T> {
    pub fn measured(value: T, uncertainty: Uncertainty, provenance: Provenance) -> Self {
        Self::Measured {
            value,
            uncertainty,
            provenance,
        }
    }

    pub fn absent(reason: AbsenceReason, provenance: Provenance) -> Self {
        Self::Absent { reason, provenance }
    }

    pub fn is_measured(&self) -> bool {
        matches!(self, Self::Measured { .. })
    }

    /// The measured value, if any.
    ///
    /// Note what this deliberately does *not* offer: there is no
    /// `value_or_default` and no `unwrap_or(0.0)`. A caller that wants a number
    /// out of a possibly-absent observation has to say what absence means in its
    /// own context, which is the whole point.
    pub fn value(&self) -> Option<&T> {
        match self {
            Self::Measured { value, .. } => Some(value),
            Self::Absent { .. } => None,
        }
    }

    pub fn uncertainty(&self) -> Option<Uncertainty> {
        match self {
            Self::Measured { uncertainty, .. } => Some(*uncertainty),
            Self::Absent { .. } => None,
        }
    }

    pub fn absence_reason(&self) -> Option<AbsenceReason> {
        match self {
            Self::Measured { .. } => None,
            Self::Absent { reason, .. } => Some(*reason),
        }
    }

    pub fn provenance(&self) -> &Provenance {
        match self {
            Self::Measured { provenance, .. } | Self::Absent { provenance, .. } => provenance,
        }
    }

    /// Inverse-variance weight, and the reason absence weighs exactly nothing.
    ///
    /// Absence is the limit `sigma^2 -> infinity`, giving weight `0.0`. This is
    /// why FR-7 is a correctness requirement rather than a style preference: an
    /// absent channel that were coded as zero would instead arrive with *full*
    /// weight on the value zero.
    pub fn precision(&self) -> f64 {
        match self {
            Self::Measured { uncertainty, .. } => uncertainty.precision(),
            Self::Absent { .. } => 0.0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn provenance() -> Provenance {
        Provenance::new("test", 20_000)
    }

    fn basis() -> UncertaintyBasis {
        UncertaintyBasis::SampleVariance { observations: 5 }
    }

    #[test]
    fn absent_evidence_weighs_exactly_nothing() {
        let absent =
            Observation::<f64>::absent(AbsenceReason::ContaminatedPeriod, provenance());
        assert_eq!(absent.precision(), 0.0);
    }

    #[test]
    fn absent_evidence_yields_no_value_rather_than_zero() {
        let absent =
            Observation::<f64>::absent(AbsenceReason::ContaminatedPeriod, provenance());
        assert_eq!(absent.value(), None);
    }

    #[test]
    fn zero_variance_is_not_a_constructible_uncertainty() {
        assert_eq!(Uncertainty::from_variance(0.0, basis()), None);
    }

    #[test]
    fn negative_variance_is_not_a_constructible_uncertainty() {
        assert_eq!(Uncertainty::from_variance(-1.0, basis()), None);
    }

    #[test]
    fn non_finite_variance_is_not_a_constructible_uncertainty() {
        assert_eq!(Uncertainty::from_variance(f64::NAN, basis()), None);
    }

    #[test]
    fn precision_is_the_reciprocal_of_variance() {
        let uncertainty = Uncertainty::from_variance(4_000.0, basis()).expect("valid variance");
        assert!((uncertainty.precision() - 1.0 / 4_000.0).abs() < f64::EPSILON);
    }

    #[test]
    fn a_measured_observation_retains_its_provenance_source() {
        let uncertainty = Uncertainty::from_variance(4_000.0, basis()).expect("valid variance");
        let observed = Observation::measured(1_200.0, uncertainty, provenance());
        assert_eq!(observed.provenance().source(), "test");
    }
}
