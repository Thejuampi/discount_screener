//! Building the Core's evidence out of what the Shell already has.
//!
//! The Core takes stated evidence and returns a posterior. Everything before
//! that — reading filings, aligning fiscal years, deciding what a number means —
//! is the Shell's job, and this module is where it happens (FR-5, FR-36).
//!
//! # Two passes, because two of the inputs are not per-issuer
//!
//! The credit curve and the growth fade rate are properties of the *cohort*, not
//! of any one name in it. Both were per-issuer quantities in the old engine and
//! both were wrong that way: the accounting coupon made cost of debt flat in
//! leverage, and the per-issuer growth autocorrelation was measured at a median
//! of 0.003 across 28 names. So [`fit_cross_section`] runs first over everybody,
//! and [`value`] then places each issuer on what it fitted.
//!
//! A fit that is not a usable curve is refused at construction rather than
//! patched: `CreditCurve::fitted` rejects a spread that does not rise with
//! leverage, and `GrowthPath::fitted` rejects a relaxation speed that does not
//! relax. When that happens every issuer that needed it refuses, loudly, instead
//! of quietly falling back to a constant.
//!
//! # What is not here yet
//!
//! **Return on capital.** Invested capital is not in the evidence the Shell
//! currently assembles, so the return arrives absent and FR-29 makes growth
//! value-neutral: the issuer is valued as though its growth exactly earns its
//! cost of capital. That is a statement of ignorance and it is deliberately not
//! a floor — the moment invested capital is available, an issuer earning below
//! its cost of capital will value below this, and one earning above will value
//! above. Every number this module produces today sits at that neutral line.
//!
//! **The forward channel.** Analyst growth estimates are not in the offline
//! evidence, so the growth posterior currently rests on the trailing channel
//! alone. `fuse` handles that as single-channel fallback with no special case.
//!
//! **Book value.** Financial issuers route to residual income, which needs
//! common equity on the balance sheet. Until that is assembled they refuse on
//! evidence, which is the correct answer rather than a gap — running the
//! operating model on them is what produced the wrong answer before.
//!
//! # One dependency on price, named rather than hidden
//!
//! The debt weight in the WACC is a market-value weight: debt over debt plus
//! market capitalization. This is standard practice and it is what the old
//! engine used, but it does mean the discount rate moves with the share price.
//! The Core never sees a price (FR-35) and this module does not clamp, target,
//! or optimize against one — but the weight is a genuine seam, and it is stated
//! here so that it can be argued about rather than discovered later.

use valuation_core::attribution::ValuationInput;
use valuation_core::capital::{cost_of_debt, wacc, CreditCurve};
use valuation_core::classification::{classify, Instrument};
use valuation_core::evidence::{
    AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis,
};
use valuation_core::numerics::robust_centre;
use valuation_core::posterior::fuse;
use valuation_core::projection::{equity_value, intrinsic_value, GrowthPath};
use valuation_core::publication::{publish, ValuationPosterior};
use valuation_core::BusinessClass;

/// Relative precision of a quantity read straight off a filing.
///
/// A balance-sheet number is reported, not estimated, so its width is rounding
/// rather than dispersion. It is here so that a quantity which has not moved in
/// five years reads as *precisely known* instead of *unmeasured* — those are
/// opposite statements, and a variance of exactly zero would say the second.
const READING_PRECISION: f64 = 1e-4;

/// The widest implied borrowing rate that can be a borrowing rate at all.
///
/// `interest / debt` is only a coupon when the two terms describe the same
/// obligation. When they do not — a year whose debt tag caught a sliver of the
/// balance while the interest line covers all of it — the ratio is not a high
/// rate, it is a category error, and it arrives looking like an extremely
/// creditworthy issuer paying 5208%.
///
/// One such point is enough to invert a cross-sectional credit fit by itself, so
/// this is a refusal rather than a clamp: the observation is dropped, never
/// rewritten. 100% is deliberately far outside anything a going concern pays, so
/// that it excludes the impossible without judging the merely distressed.
const MAX_PLAUSIBLE_COUPON_BPS: f64 = 10_000.0;

/// How many recent years a cash-flow *level* is read from.
///
/// Deliberately much shorter than the history the cross-sectional fits use, and
/// for the opposite reason. Persistence and the credit curve pool across issuers
/// and want every observation they can get. A level is a statement about *this
/// business now*, and Amazon's 2008 free cash flow is not evidence about Amazon's
/// 2025 level — it is evidence about a different company that shared a name.
///
/// Fitting one line across nineteen years of compounding also underestimates the
/// endpoint badly, because the series is exponential and the line is not. A short
/// window keeps the linear form honest without needing logs, which matters
/// because free cash flow is regularly negative and has no logarithm.
const LEVEL_WINDOW_YEARS: usize = 5;

/// Fewest annual observations that make a level and a dispersion measurable.
/// Below this the issuer has a history, not a sample.
///
/// Three is the arithmetic minimum for a trend: two degrees of freedom go to the
/// line and one is left to say how far the history scattered around it. A width
/// estimated from a single residual is a very noisy width — but it is unbiased,
/// and it is *wide*, which is the honest reading of three points. Refusing
/// instead would throw away a determined level to avoid reporting an uncertain
/// one, which is the failure mode this whole design exists to remove.
const MIN_ANNUAL_OBSERVATIONS: usize = 3;

/// Issuers a credit *curve* needs, as against a single issuer's trend.
///
/// The curve prices every levered name in the run, so it answers to a different
/// bar than one issuer's own history does. Stated here rather than left implicit
/// inside the shared fit, so that lowering one never silently lowers the other.
const MIN_CREDIT_OBSERVATIONS: usize = 4;

/// Market-frame quantities, identical for every issuer in a run.
#[derive(Debug, Clone, Copy)]
pub struct MarketFrame {
    pub risk_free_bps: f64,
    pub equity_risk_premium_bps: f64,
    pub terminal_growth_bps: f64,
    /// The day every frame quantity was observed, as an epoch day.
    pub observed_epoch_day: i64,
}

/// One fiscal year of an issuer's drivers, already aligned by the Shell.
#[derive(Debug, Clone)]
pub struct IssuerAnnual {
    pub year: i32,
    pub operating_cash_flow: f64,
    pub capital_expenditure: f64,
    pub revenue: f64,
    pub interest_expense: f64,
    pub debt: Option<f64>,
    pub marginal_tax_bps: i32,
}

/// Everything the Shell knows about one issuer, before any of it is a valuation.
#[derive(Debug, Clone)]
pub struct IssuerEvidence {
    pub symbol: String,
    pub sector: String,
    pub industry: String,
    pub shares_outstanding: Option<u64>,
    pub market_capitalization: Option<f64>,
    pub total_debt: f64,
    pub total_cash: f64,
    pub beta_millis: i32,
    pub annuals: Vec<IssuerAnnual>,
}

/// The quantities fitted across the cohort rather than measured per issuer.
#[derive(Debug, Clone)]
pub struct CrossSection {
    credit_curve: Option<CreditCurve>,
    growth_path: Option<GrowthPath>,
    beta_variance: f64,
    /// Kept so a run can be read after the fact without refitting.
    pub diagnostics: CrossSectionDiagnostics,
}

/// What the two fits saw, so a refusal to fit can be read rather than guessed at.
#[derive(Debug, Clone, Default)]
pub struct CrossSectionDiagnostics {
    pub credit_observations: usize,
    pub credit_intercept_bps: f64,
    pub credit_leverage_slope: f64,
    pub credit_coverage_slope: f64,
    pub growth_pairs: usize,
    /// Pooled growth observations the cohort's own dispersion excluded. How
    /// contaminated the cross-section was.
    pub growth_pooled_discarded: usize,
    /// Consecutive-year pairs that left the fit because one of their two years
    /// was excluded. How much fit evidence that contamination cost, which is a
    /// different number: an excluded year in the middle of a series costs two
    /// pairs, one at either end costs one.
    pub growth_pairs_dropped: usize,
    pub growth_persistence: f64,
    pub fade_per_year: f64,
    pub beta_standard_deviation: f64,
}

impl CrossSection {
    pub fn credit_curve(&self) -> Option<&CreditCurve> {
        self.credit_curve.as_ref()
    }

    pub fn growth_path(&self) -> Option<&GrowthPath> {
        self.growth_path.as_ref()
    }
}

/// Fit the cohort-level quantities: the credit curve, the fade rate, and the
/// dispersion that stands in for how precisely any one beta is known.
pub fn fit_cross_section(issuers: &[IssuerEvidence], frame: &MarketFrame) -> CrossSection {
    let provenance = Provenance::new("cross_section", frame.observed_epoch_day);
    let mut diagnostics = CrossSectionDiagnostics::default();

    let credit_curve = fit_credit_curve(issuers, frame, &provenance, &mut diagnostics);
    let growth_path = fit_growth_path(issuers, frame, &provenance, &mut diagnostics);
    let beta_variance = fit_beta_dispersion(issuers, &mut diagnostics);

    CrossSection {
        credit_curve,
        growth_path,
        beta_variance,
        diagnostics,
    }
}

/// Regress the log spread each issuer actually pays on its leverage.
///
/// The spread is observed, not assumed: interest expense over debt is what the
/// issuer's lenders charged, and subtracting the risk-free rate leaves the
/// credit component. Fitting the log makes the exponential form linear, so this
/// is ordinary least squares on one regressor and nothing more.
///
/// # Why coverage is fitted at a slope of zero
///
/// The three quantities are not independent. With leverage `debt/OCF`, coverage
/// `OCF/interest` and the observed rate `interest/debt`,
///
/// ```text
/// leverage * coverage * rate = 1
/// ```
///
/// identically, for every issuer, by arithmetic. So regressing the observed rate
/// on both leverage and coverage does not measure how lenders price risk; it
/// recovers that identity, and it would recover it just as well from numbers
/// with no economic content at all. Leverage alone is admissible because
/// `debt/OCF` contains no interest term, which is the whole reason the response
/// can be regressed on it.
///
/// A coverage slope of zero is a real curve, not a degenerate one — the Core
/// accepts it, and the resulting spread is still strictly monotone in leverage,
/// which is what FR-20 requires. What the zero says is that this evidence cannot
/// separate the two, and separating them needs a spread observed *outside* the
/// issuer's own accounts: a rating, a bond quote, a market credit spread. Until
/// one of those is wired in, claiming a coverage effect would be fitting noise
/// and calling it a credit market.
fn fit_credit_curve(
    issuers: &[IssuerEvidence],
    frame: &MarketFrame,
    provenance: &Provenance,
    diagnostics: &mut CrossSectionDiagnostics,
) -> Option<CreditCurve> {
    let observations: Vec<(f64, f64)> = issuers
        .iter()
        .filter_map(|issuer| {
            let structure = issuer.capital_structure()?;
            let spread = structure.effective_rate_bps - frame.risk_free_bps;
            (spread > 0.0).then_some((structure.leverage, spread.ln()))
        })
        .collect();
    diagnostics.credit_observations = observations.len();
    if observations.len() < MIN_CREDIT_OBSERVATIONS {
        return None;
    }

    let fit = least_squares(&observations)?;
    diagnostics.credit_intercept_bps = fit.intercept.exp();
    diagnostics.credit_leverage_slope = fit.slope;
    diagnostics.credit_coverage_slope = 0.0;

    // `fitted` refuses a curve that is not monotone the way credit is, so a fit
    // whose leverage slope came out flat or negative produces no curve at all
    // rather than a curve that misprices every levered name in the cohort.
    CreditCurve::fitted(fit.intercept.exp(), fit.slope, 0.0, provenance.clone())
}

/// Which issuer's which year one pooled growth observation is.
///
/// The pooled sample is a flatten of per-issuer series, so a position in it
/// identifies nothing on its own: the same index means a different issuer's year
/// the moment any series changes length. The centre reports its exclusions
/// positionally, against the vector it was given, so those positions are
/// resolved back into keys once — here — and everything downstream speaks in
/// keys. Carrying the bare index into the pair construction would silently kill
/// the wrong issuer's years, which nothing that counts dropped pairs could see.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct GrowthKey {
    issuer: usize,
    /// Position within that issuer's growth series, oldest first. A pair is
    /// `(step, step + 1)`, so it exists only between consecutive steps.
    step: usize,
}

/// Estimate how fast growth rates converge, pooled across the cohort.
///
/// This is the estimator that replaces FR-16. The probe that killed the
/// per-issuer version regressed each firm's growth on its own lag *after*
/// removing that firm's own mean, which by construction leaves only noise. Here
/// the mean removed is the cohort's, so the regression measures what it is meant
/// to: whether a name growing faster than its peers this year is still doing so
/// next year. Persistence `rho` becomes a relaxation speed as `k = -ln(rho)`.
///
/// # The centre and the pairs see the same evidence
///
/// The centre every pair is de-meaned by is robust, so a contaminated year is
/// not in it. That year is then also kept out of the pairs, because a
/// cross-section that excludes an observation from its location and then feeds
/// the same observation to the regression that uses that location has not
/// excluded it at all — it has only stopped counting it once.
///
/// A pair is a consecutive-year transition. Dropping a year therefore costs both
/// pairs it belonged to, and **no pair is built across the hole**: a jump from
/// the year before the exclusion to the year after it is not an annual
/// transition, and inventing one would be a different fabrication from the one
/// this is fixing.
fn fit_growth_path(
    issuers: &[IssuerEvidence],
    frame: &MarketFrame,
    provenance: &Provenance,
    diagnostics: &mut CrossSectionDiagnostics,
) -> Option<GrowthPath> {
    let series: Vec<Vec<f64>> = issuers
        .iter()
        .map(IssuerEvidence::annual_revenue_growth)
        .collect();
    let observations: Vec<(GrowthKey, f64)> = series
        .iter()
        .enumerate()
        .flat_map(|(issuer, growth)| {
            growth
                .iter()
                .enumerate()
                .map(move |(step, value)| (GrowthKey { issuer, step }, *value))
        })
        .collect();

    // The pooled sample is this vector's values in this vector's order, so a
    // position reported against one is a position in the other.
    let pooled: Vec<f64> = observations.iter().map(|(_, value)| *value).collect();
    let centre = robust_centre(&pooled).ok()?;
    let excluded: Vec<GrowthKey> = centre
        .outliers()
        .iter()
        .filter_map(|position| observations.get(*position).map(|(key, _)| *key))
        .collect();
    diagnostics.growth_pooled_discarded = centre.discarded();

    let pairs: Vec<(f64, f64)> = series
        .iter()
        .enumerate()
        .flat_map(|(issuer, growth)| {
            growth
                .windows(2)
                .enumerate()
                // The two years a transition spans. Either one excluded takes
                // the transition with it, and the next window starts after it
                // rather than reaching across it.
                .filter(|(step, _)| {
                    ![*step, step + 1].iter().any(|spanned| {
                        excluded.contains(&GrowthKey {
                            issuer,
                            step: *spanned,
                        })
                    })
                })
                .map(|(_, pair)| (pair[0] - centre.centre(), pair[1] - centre.centre()))
                .collect::<Vec<(f64, f64)>>()
        })
        .collect();

    // Every transition the cohort could have contributed, against the ones that
    // survived. The difference is what the contamination cost the fit, and it is
    // a different quantity from the number of contaminated years.
    let transitions: usize = series
        .iter()
        .map(|growth| growth.len().saturating_sub(1))
        .sum();
    diagnostics.growth_pairs = pairs.len();
    diagnostics.growth_pairs_dropped = transitions - pairs.len();

    // Through the origin, because both sides are already deviations from the
    // pooled mean and an intercept would let the fit relocate that mean.
    let cross: f64 = pairs.iter().map(|(lag, next)| lag * next).sum();
    let square: f64 = pairs.iter().map(|(lag, _)| lag * lag).sum();
    let persistence = cross / square;
    diagnostics.growth_persistence = persistence;

    // `k = -ln(rho)` is only a relaxation speed for `rho` in `(0, 1)`. A
    // persistence at or below zero is not a very fast fade, it is the statement
    // that this year's position tells you nothing about next year's — and one at
    // or above one is a path that never converges. Neither has a `k`, and
    // computing `-ln` of a non-positive number yields a NaN that would reach the
    // Core's constructor as if it were a measurement.
    if !(persistence > 0.0 && persistence < 1.0) {
        diagnostics.fade_per_year = f64::NAN;
        return None;
    }
    let fade = -persistence.ln();
    diagnostics.fade_per_year = fade;
    GrowthPath::fitted(frame.terminal_growth_bps, fade, provenance.clone())
}

/// How precisely any one beta is known, taken from how much betas differ.
///
/// A published beta is an estimate and the Shell is not given its standard
/// error. The cohort's dispersion is the stand-in: it is the same reasoning that
/// already shrinks a company beta toward an industry beta, and it errs toward a
/// wider interval rather than a narrower one, which is the right direction to
/// err in when the quantity is unknown.
///
/// A cohort whose betas are all identical yields no dispersion and therefore no
/// cost of equity, and every member refuses. That is the correct answer to
/// evidence that says nothing about how precisely beta is known — the wrong one
/// would be a width invented to keep the run producing numbers.
fn fit_beta_dispersion(
    issuers: &[IssuerEvidence],
    diagnostics: &mut CrossSectionDiagnostics,
) -> f64 {
    let betas: Vec<f64> = issuers
        .iter()
        .map(|issuer| f64::from(issuer.beta_millis) / 1_000.0)
        .collect();
    let variance = sample_variance(&betas).unwrap_or(0.0);
    diagnostics.beta_standard_deviation = variance.sqrt();
    variance
}

/// Value one issuer against a fitted cross-section.
pub fn value(
    issuer: &IssuerEvidence,
    frame: &MarketFrame,
    cross_section: &CrossSection,
) -> ValuationPosterior {
    let class = classify(&issuer.sector, &issuer.industry, Instrument::CommonEquity);
    let shares = issuer.diluted_shares(frame);

    // Only the operating path is assembled today. A financial issuer classifies
    // correctly and then refuses for the input it actually lacks, rather than
    // being valued by a model that does not describe it.
    if class == BusinessClass::FinancialServices {
        return publish(
            class,
            &valuation_core::Valuation::new(
                Observation::absent(
                    AbsenceReason::ProviderUnavailable,
                    Provenance::new("book_value", frame.observed_epoch_day),
                ),
                vec![],
            ),
            &shares,
        );
    }

    let discount_rate = issuer.discount_rate(frame, cross_section);
    let firm = intrinsic_value(
        &issuer.base_cash_flow(frame),
        &issuer.growth_posterior(frame),
        cross_section.growth_path().unwrap_or(&unfitted_path(frame)),
        &issuer.return_on_capital(frame),
        &discount_rate,
    );
    publish(
        class,
        &equity_value(&firm, &issuer.net_debt(frame)),
        &shares,
    )
}

/// A path that cannot be fitted is not a path, and a valuation without one has
/// to refuse. `intrinsic_value` takes the path by reference because an unfitted
/// one is unrepresentable, so the refusal is arranged here — a zero terminal
/// rate with an unusable fade that `fitted` would itself reject.
fn unfitted_path(frame: &MarketFrame) -> GrowthPath {
    GrowthPath::fitted(
        frame.terminal_growth_bps,
        f64::MIN_POSITIVE,
        Provenance::new("unfitted_growth_path", frame.observed_epoch_day),
    )
    .expect("a positive fade rate is a path")
}

/// An issuer's leverage, coverage and the rate its lenders actually charge.
pub struct CapitalStructure {
    pub leverage: f64,
    pub coverage: f64,
    pub effective_rate_bps: f64,
}

impl IssuerEvidence {
    /// The structure the credit fit reads, exposed so a diagnostic reports the
    /// same evidence the fit consumed rather than a second reading of it.
    pub fn credit_evidence(&self) -> Option<CapitalStructure> {
        self.capital_structure()
    }

    /// Year-over-year revenue growth in basis points, oldest first, exposed for
    /// the same reason.
    pub fn revenue_growth(&self) -> Vec<f64> {
        self.annual_revenue_growth()
    }

    fn provenance(&self, source: &'static str, frame: &MarketFrame) -> Provenance {
        Provenance::new(source, frame.observed_epoch_day)
    }

    fn latest(&self) -> Option<&IssuerAnnual> {
        self.annuals.iter().max_by_key(|annual| annual.year)
    }

    /// Free cash flow to the firm, one value per fiscal year, oldest first.
    ///
    /// Interest is added back after tax because the cash flow being discounted
    /// belongs to every provider of capital, and the tax shield on interest is
    /// already carried by the after-tax cost of debt inside the WACC. Counting
    /// it in both places is the double count this ordering avoids.
    fn free_cash_flow(&self) -> Vec<f64> {
        let mut ordered: Vec<&IssuerAnnual> = self.annuals.iter().collect();
        ordered.sort_by_key(|annual| annual.year);
        ordered
            .iter()
            .map(|annual| {
                let after_tax = 1.0 - f64::from(annual.marginal_tax_bps) / 10_000.0;
                annual.operating_cash_flow - annual.capital_expenditure
                    + annual.interest_expense * after_tax
            })
            .collect()
    }

    /// The current sustainable cash flow, read off a trend rather than off the
    /// last year.
    ///
    /// The quantity the model needs is the level cash flow is *at* now, which
    /// then compounds along the growth path. The single most recent observation
    /// is the noisiest available estimate of that: one capital-expenditure
    /// trough or one working-capital swing moves a perpetuity by its full
    /// amount. Fitting a line through the history and reading it at the latest
    /// year uses every observation, and the scatter around that line is a
    /// principled width rather than an assumed one.
    ///
    /// This is a level estimator, not a smoothing preference. It does not damp
    /// growth — the fitted line is free to be steep — it damps the *noise around*
    /// growth, and its residual variance says how much noise there was.
    ///
    /// Only the last `LEVEL_WINDOW_YEARS` are used, even when far more history is
    /// available. See that constant for why a level answers to a shorter window
    /// than a cross-sectional fit does.
    fn base_cash_flow(&self, frame: &MarketFrame) -> Observation<f64> {
        let provenance = self.provenance("free_cash_flow", frame);
        let mut years: Vec<f64> = self
            .annuals
            .iter()
            .map(|annual| f64::from(annual.year))
            .collect();
        years.sort_by(|left, right| left.partial_cmp(right).expect("years are finite"));
        let flows = self.free_cash_flow();
        if flows.len() < MIN_ANNUAL_OBSERVATIONS || flows.len() != years.len() {
            return Observation::absent(AbsenceReason::InsufficientObservations, provenance);
        }

        let full: Vec<(f64, f64)> = years.iter().copied().zip(flows.iter().copied()).collect();
        let observations = &full[full.len().saturating_sub(LEVEL_WINDOW_YEARS)..];
        let Some(fit) = least_squares(observations) else {
            return Observation::absent(AbsenceReason::InsufficientObservations, provenance);
        };
        let latest_year = *years.last().expect("a non-empty history");
        let level = fit.intercept + fit.slope * latest_year;

        // Residual variance about the fitted line, on `n - 2` degrees of
        // freedom because the line itself consumed two. The reading precision of
        // the flows themselves is added on top, so that a history which happens
        // to sit exactly on its line reads as *precisely determined* rather than
        // as unmeasured — a variance of exactly zero would say the second.
        let degrees_of_freedom = (observations.len() - 2) as f64;
        let scatter = observations
            .iter()
            .map(|(year, flow)| (flow - (fit.intercept + fit.slope * year)).powi(2))
            .sum::<f64>()
            / degrees_of_freedom;
        let variance = scatter + reading_variance(level);

        measured_or_absent(
            level,
            variance,
            UncertaintyBasis::SampleVariance {
                observations: observations.len() as u32,
            },
            provenance,
        )
    }

    /// Year-over-year revenue growth in basis points, oldest first, as a
    /// continuously compounded rate.
    ///
    /// `ln(r1/r0)`, not `r1/r0 - 1`. The Core's path is `g(t) = g_inf + (g_0 -
    /// g_inf)e^{-kt}` integrated against `e^{-rt}`: a continuous rate throughout.
    /// Feeding it a simple percentage change is a unit mismatch that is invisible
    /// at 5% and enormous at 200%, which is precisely where this cohort lives —
    /// seventeen years of small-cap history contains first-product years whose
    /// simple growth runs to thousands of percent.
    ///
    /// The mismatch was also corrupting the cross-sectional fits. A regressor
    /// with a standard deviation of 33451 bps, almost all of it a handful of
    /// enormous observations, produces a slope near zero whatever the truth is;
    /// on the log scale a 30-fold year is 34000 bps rather than 290000, and it
    /// stops drowning every other observation in the cohort.
    fn annual_revenue_growth(&self) -> Vec<f64> {
        let mut ordered: Vec<&IssuerAnnual> = self.annuals.iter().collect();
        ordered.sort_by_key(|annual| annual.year);
        ordered
            .windows(2)
            .filter(|pair| pair[0].revenue > 0.0 && pair[1].revenue > 0.0)
            .map(|pair| (pair[1].revenue / pair[0].revenue).ln() * 10_000.0)
            .collect()
    }

    /// The trailing channel, fused with a forward channel that is not there yet.
    ///
    /// The centre of the realized growth rates, with the standard error of that
    /// centre as its width — the level is what the persistence probe found to be
    /// the estimable quantity, and its standard error is what says how well.
    ///
    /// # Why all three parts come from the centre and none from the raw series
    ///
    /// This channel supplies both the point estimate and the precision that
    /// `fuse` weights by, and `fuse` weights by `1/variance` and nothing else.
    /// So a robust centre paired with the untrimmed variance of the same series
    /// would be strictly worse than changing nothing: the level would be clean
    /// while the width still carried the contamination, and the weight this
    /// channel takes against a forward channel would move for a reason that is
    /// not evidence. The count is the retained count for the same reason —
    /// reporting eighteen observations behind a width computed from fifteen
    /// overstates precision in a third place.
    ///
    /// An issuer whose growth history has no width — a flat or nearly flat
    /// revenue line — refuses here rather than publishing a centre of certainty.
    /// A variance of zero is infinite precision under inverse-variance fusion,
    /// which would let one degenerate history dominate a posterior outright.
    fn growth_posterior(&self, frame: &MarketFrame) -> Observation<f64> {
        let provenance = self.provenance("trailing_growth", frame);
        let growth = self.annual_revenue_growth();
        let trailing = match robust_centre(&growth) {
            Ok(centre) => measured_or_absent(
                centre.centre(),
                centre.variance_of_centre(),
                UncertaintyBasis::SampleVariance {
                    observations: centre.retained() as u32,
                },
                provenance,
            ),
            Err(reason) => Observation::absent(reason, provenance),
        };
        let forward = Observation::absent(
            AbsenceReason::ProviderUnavailable,
            self.provenance("forward_growth", frame),
        );
        fuse(&[trailing, forward]).estimate().clone()
    }

    /// Absent, and deliberately so — see the module documentation. FR-29 reads
    /// this as "we do not know whether this growth creates value", which is a
    /// true statement about the evidence rather than a floor on the answer.
    fn return_on_capital(&self, frame: &MarketFrame) -> Observation<f64> {
        Observation::absent(
            AbsenceReason::ProviderUnavailable,
            self.provenance("invested_capital", frame),
        )
    }

    /// The most recent year that reports every term the structure is built from.
    ///
    /// Not simply the most recent year. The latest filing is the least completely
    /// tagged one in practice — issuers that plainly carry debt (an interest
    /// expense in the hundreds of millions) routinely have no resolvable debt
    /// figure for their newest fiscal year, because the tag the extractor
    /// recognises has not appeared yet. Reading the latest year unconditionally
    /// turns that into "this issuer has no debt", which is a fabricated
    /// measurement, and it silently drops the issuer from the credit fit.
    ///
    /// Falling back one year trades a little staleness for a real reading. The
    /// staleness is bounded and visible; the fabrication would be neither.
    fn capital_structure(&self) -> Option<CapitalStructure> {
        let mut years: Vec<&IssuerAnnual> = self.annuals.iter().collect();
        years.sort_by_key(|annual| std::cmp::Reverse(annual.year));
        years.into_iter().find_map(|annual| {
            let debt = annual.debt.filter(|debt| *debt > 0.0)?;
            if annual.interest_expense <= 0.0 || annual.operating_cash_flow <= 0.0 {
                return None;
            }
            let effective_rate_bps = annual.interest_expense / debt * 10_000.0;
            (effective_rate_bps <= MAX_PLAUSIBLE_COUPON_BPS).then_some(CapitalStructure {
                leverage: debt / annual.operating_cash_flow,
                coverage: annual.operating_cash_flow / annual.interest_expense,
                effective_rate_bps,
            })
        })
    }

    /// Leverage and coverage as observations, widened by how much each has moved
    /// across the history — a structure that has been stable is known better
    /// than one that has not.
    ///
    /// The reading precision is added on top rather than replaced by the
    /// movement, so a single year of history yields a narrow width and a sample
    /// size of one. That understates how much the ratio could move, and the
    /// basis records the sample size precisely so the understatement is visible
    /// rather than silent.
    fn structure_observations(&self, frame: &MarketFrame) -> (Observation<f64>, Observation<f64>) {
        let provenance = self.provenance("capital_structure", frame);
        let Some(structure) = self.capital_structure() else {
            return (
                Observation::absent(AbsenceReason::NotReported, provenance.clone()),
                Observation::absent(AbsenceReason::NotReported, provenance),
            );
        };
        let history: Vec<(f64, f64)> = self
            .annuals
            .iter()
            .filter_map(|annual| {
                let debt = annual.debt.filter(|debt| *debt > 0.0)?;
                (annual.interest_expense > 0.0 && annual.operating_cash_flow > 0.0).then(|| {
                    (
                        debt / annual.operating_cash_flow,
                        annual.operating_cash_flow / annual.interest_expense,
                    )
                })
            })
            .collect();
        let leverages: Vec<f64> = history.iter().map(|pair| pair.0).collect();
        let coverages: Vec<f64> = history.iter().map(|pair| pair.1).collect();
        let basis = UncertaintyBasis::SampleVariance {
            observations: history.len() as u32,
        };
        (
            measured_or_absent(
                structure.leverage,
                sample_variance(&leverages).unwrap_or(0.0) + reading_variance(structure.leverage),
                basis,
                provenance.clone(),
            ),
            measured_or_absent(
                structure.coverage,
                sample_variance(&coverages).unwrap_or(0.0) + reading_variance(structure.coverage),
                basis,
                provenance,
            ),
        )
    }

    /// The capital asset pricing model, with the beta's width from the cohort.
    fn cost_of_equity(
        &self,
        frame: &MarketFrame,
        cross_section: &CrossSection,
    ) -> Observation<f64> {
        let beta = f64::from(self.beta_millis) / 1_000.0;
        measured_or_absent(
            frame.risk_free_bps + beta * frame.equity_risk_premium_bps,
            frame.equity_risk_premium_bps.powi(2) * cross_section.beta_variance,
            UncertaintyBasis::Propagated { inputs: 1 },
            self.provenance("cost_of_equity", frame),
        )
    }

    /// Market-value debt weight. See the module note on the one place a price
    /// enters this pipeline.
    fn debt_weight_bps(&self) -> f64 {
        let equity = self.market_capitalization.unwrap_or(0.0).max(0.0);
        let debt = self.total_debt.max(0.0);
        let capital = debt + equity;
        if capital <= 0.0 {
            return 0.0;
        }
        (debt / capital * 10_000.0).clamp(0.0, 10_000.0)
    }

    fn discount_rate(&self, frame: &MarketFrame, cross_section: &CrossSection) -> Observation<f64> {
        let (leverage, coverage) = self.structure_observations(frame);
        let debt_cost = cost_of_debt(
            cross_section.credit_curve(),
            &leverage,
            &coverage,
            frame.risk_free_bps,
        );
        let tax_bps = self
            .latest()
            .map(|annual| f64::from(annual.marginal_tax_bps))
            .unwrap_or(0.0);
        wacc(
            &self.cost_of_equity(frame, cross_section),
            &debt_cost,
            self.debt_weight_bps(),
            tax_bps,
        )
    }

    /// Net debt is a balance-sheet reading, so its width is the rounding of the
    /// reading itself rather than an estimate's dispersion. It is negligible
    /// next to the cash-flow and rate terms, and the attribution shows exactly
    /// that rather than hiding it.
    fn net_debt(&self, frame: &MarketFrame) -> Observation<f64> {
        let net = self.total_debt - self.total_cash;
        measured_or_absent(
            net,
            reading_variance(net),
            UncertaintyBasis::SampleVariance { observations: 1 },
            self.provenance("net_debt", frame),
        )
    }

    fn diluted_shares(&self, frame: &MarketFrame) -> Observation<f64> {
        let provenance = self.provenance("diluted_shares", frame);
        let Some(shares) = self.shares_outstanding.filter(|shares| *shares > 0) else {
            return Observation::absent(AbsenceReason::NotReported, provenance);
        };
        let shares = shares as f64;
        measured_or_absent(
            shares,
            reading_variance(shares),
            UncertaintyBasis::SampleVariance { observations: 1 },
            provenance,
        )
    }
}

/// The variance implied by reading a quantity off a filing at
/// [`READING_PRECISION`], never exactly zero.
fn reading_variance(value: f64) -> f64 {
    (value.abs() * READING_PRECISION)
        .powi(2)
        .max(f64::MIN_POSITIVE)
}

/// A value with a variance, or the absence that a variance of zero implies.
///
/// A zero-width measurement is not a measurement (FR-6), so this refuses rather
/// than substituting a nominal width — the Core's own constructors take the same
/// position and this keeps the Shell from being the place the rule is bent.
fn measured_or_absent(
    value: f64,
    variance: f64,
    basis: UncertaintyBasis,
    provenance: Provenance,
) -> Observation<f64> {
    match Uncertainty::from_variance(variance, basis) {
        Some(uncertainty) => Observation::measured(value, uncertainty, provenance),
        None => Observation::absent(AbsenceReason::InsufficientObservations, provenance),
    }
}

fn mean(values: &[f64]) -> Option<f64> {
    (!values.is_empty()).then(|| values.iter().sum::<f64>() / values.len() as f64)
}

fn sample_variance(values: &[f64]) -> Option<f64> {
    if values.len() < 2 {
        return None;
    }
    let mean = mean(values)?;
    Some(
        values
            .iter()
            .map(|value| (value - mean).powi(2))
            .sum::<f64>()
            / (values.len() - 1) as f64,
    )
}

pub(crate) struct LinearFit {
    pub(crate) intercept: f64,
    pub(crate) slope: f64,
}

/// Ordinary least squares of `y` on one regressor and an intercept.
///
/// Small and explicit rather than a linear-algebra dependency, because the Core
/// has none and the Shell should not grow one for a two-parameter fit.
pub(crate) fn least_squares(observations: &[(f64, f64)]) -> Option<LinearFit> {
    // The arithmetic minimum: two parameters plus one residual, so that a caller
    // wanting a scatter can compute one at all. Callers whose fit answers to a
    // higher bar than determinacy state that bar at their own call site.
    if observations.len() < 3 {
        return None;
    }
    let predictors: Vec<f64> = observations.iter().map(|pair| pair.0).collect();
    let responses: Vec<f64> = observations.iter().map(|pair| pair.1).collect();
    let (mean_predictor, mean_response) = (mean(&predictors)?, mean(&responses)?);

    let cross: f64 = observations
        .iter()
        .map(|(predictor, response)| (predictor - mean_predictor) * (response - mean_response))
        .sum();
    let square: f64 = predictors
        .iter()
        .map(|predictor| (predictor - mean_predictor).powi(2))
        .sum();
    if square == 0.0 || !square.is_finite() {
        return None;
    }

    let slope = cross / square;
    let intercept = mean_response - slope * mean_predictor;
    (slope.is_finite() && intercept.is_finite()).then_some(LinearFit { intercept, slope })
}

/// The published median in cents, for callers that only want the number.
pub fn median_cents(posterior: &ValuationPosterior) -> Option<i64> {
    match posterior {
        ValuationPosterior::Published { median_cents, .. } => Some(*median_cents),
        ValuationPosterior::Refused { .. } => None,
    }
}

/// The input holding the largest share of the published width, if any.
pub fn widest_input(posterior: &ValuationPosterior) -> Option<ValuationInput> {
    let ValuationPosterior::Published { attribution, .. } = posterior else {
        return None;
    };
    attribution
        .iter()
        .max_by_key(|share| share.share_bps())
        .map(|share| share.input())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame() -> MarketFrame {
        MarketFrame {
            risk_free_bps: 430.0,
            equity_risk_premium_bps: 450.0,
            terminal_growth_bps: 300.0,
            observed_epoch_day: 20_663,
        }
    }

    /// One issuer, described the way the fit has to see it: leverage and the
    /// coupon its lenders charge are chosen independently, because in real
    /// evidence they are and the whole point of the curve is the link between
    /// them.
    fn issuer(symbol: &str, growth: f64, leverage: f64, rate_bps: f64) -> IssuerEvidence {
        // Growth wobbles around its mean rather than repeating it exactly. A
        // perfectly constant series has no dispersion, and no dispersion is no
        // measurement -- real revenue never behaves that way, and a fixture that
        // did would be testing a case the model correctly refuses.
        let wobble = [0.0, -0.01, 0.015, -0.005, 0.02];
        let mut revenue = 1_000.0;
        let annuals: Vec<IssuerAnnual> = (0..5)
            .map(|step| {
                revenue *= 1.0 + growth + wobble[step as usize];
                let cash_flow = revenue * 0.2;
                let debt = leverage * cash_flow;
                IssuerAnnual {
                    year: 2021 + step,
                    operating_cash_flow: cash_flow,
                    capital_expenditure: cash_flow * 0.2,
                    revenue,
                    interest_expense: debt * rate_bps / 10_000.0,
                    debt: Some(debt),
                    marginal_tax_bps: 2_100,
                }
            })
            .collect();
        let total_debt = annuals.last().and_then(|annual| annual.debt).unwrap_or(0.0);
        IssuerEvidence {
            symbol: symbol.to_string(),
            sector: "Technology".to_string(),
            industry: "Software - Infrastructure".to_string(),
            shares_outstanding: Some(100),
            market_capitalization: Some(4_000.0),
            total_debt,
            total_cash: 100.0,
            // Betas differ across the cohort, because a cohort whose betas are
            // identical carries no evidence about how precisely any one of them
            // is known and correctly refuses to produce a cost of equity.
            beta_millis: (800.0 + leverage * 200.0) as i32,
            annuals,
        }
    }

    /// Six issuers whose coupons genuinely rise with leverage, which is what a
    /// fit has to be able to see before any test of it means anything.
    fn cohort() -> Vec<IssuerEvidence> {
        vec![
            issuer("AAA", 0.04, 0.5, 500.0),
            issuer("BBB", 0.06, 1.0, 600.0),
            issuer("CCC", 0.08, 1.5, 720.0),
            issuer("DDD", 0.10, 2.0, 860.0),
            issuer("EEE", 0.05, 2.5, 1_030.0),
            issuer("FFF", 0.07, 3.0, 1_240.0),
        ]
    }

    /// An issuer whose revenue line is stated directly, for tests about *which
    /// years* the growth fit uses rather than about the level.
    ///
    /// At most the five years [`issuer`] builds; a shorter line shortens the
    /// history, which is how a two-transition issuer is expressed.
    fn issuer_with_revenues(symbol: &str, revenues: &[f64]) -> IssuerEvidence {
        let mut evidence = issuer(symbol, 0.05, 1.0, 600.0);
        evidence.annuals.truncate(revenues.len());
        for (annual, revenue) in evidence.annuals.iter_mut().zip(revenues) {
            annual.revenue = *revenue;
        }
        evidence
    }

    /// The clean cohort with one more name whose revenue jumps a hundredfold in
    /// a single year and stays there — one contaminated transition, in the
    /// middle of the series, with two ordinary years on either side of it.
    fn cohort_with(contaminated: IssuerEvidence) -> Vec<IssuerEvidence> {
        let mut cohort = cohort();
        cohort.push(contaminated);
        cohort
    }

    fn growth_diagnostics(issuers: &[IssuerEvidence]) -> CrossSectionDiagnostics {
        fit_cross_section(issuers, &frame()).diagnostics
    }

    /// An issuer whose cash flows are stated directly, for tests about the level
    /// estimator rather than about the cross-section.
    fn issuer_with_cash_flows(flows: &[f64]) -> IssuerEvidence {
        let mut evidence = issuer("LVL", 0.05, 1.0, 600.0);
        evidence.annuals = flows
            .iter()
            .enumerate()
            .map(|(step, flow)| IssuerAnnual {
                year: 2021 + step as i32,
                operating_cash_flow: *flow,
                capital_expenditure: 0.0,
                revenue: 1_000.0,
                interest_expense: 0.0,
                debt: None,
                marginal_tax_bps: 2_100,
            })
            .collect();
        evidence
    }

    #[test]
    fn a_history_on_a_straight_line_reads_its_level_at_the_latest_year() {
        let base =
            issuer_with_cash_flows(&[100.0, 200.0, 300.0, 400.0, 500.0]).base_cash_flow(&frame());
        assert!((base.value().copied().expect("a determined level") - 500.0).abs() < 1e-9);
    }

    /// The reason the level comes off a trend and not off the last row. A single
    /// final-year windfall is partly history and partly noise; taking it at face
    /// value would move a perpetuity by its whole amount.
    #[test]
    fn a_final_year_windfall_moves_the_level_by_less_than_the_windfall() {
        let steady =
            issuer_with_cash_flows(&[100.0, 200.0, 300.0, 400.0, 500.0]).base_cash_flow(&frame());
        let shocked =
            issuer_with_cash_flows(&[100.0, 200.0, 300.0, 400.0, 900.0]).base_cash_flow(&frame());
        let (steady, shocked) = (
            steady.value().copied().expect("a determined level"),
            shocked.value().copied().expect("a determined level"),
        );
        assert!(
            shocked > steady && shocked < 900.0,
            "level {shocked} should sit between {steady} and the windfall"
        );
    }

    /// And the width the scatter implies is charged, rather than assumed: the
    /// shocked history is a less certain reading of the same level.
    #[test]
    fn scatter_about_the_trend_widens_the_level() {
        let steady =
            issuer_with_cash_flows(&[100.0, 200.0, 300.0, 400.0, 500.0]).base_cash_flow(&frame());
        let shocked =
            issuer_with_cash_flows(&[100.0, 200.0, 300.0, 400.0, 900.0]).base_cash_flow(&frame());
        assert!(
            shocked.uncertainty().map(Uncertainty::variance)
                > steady.uncertainty().map(Uncertainty::variance)
        );
    }

    /// Revenue growth is a continuously compounded rate, because that is the
    /// only thing the Core's exponential path can consume.
    #[test]
    fn revenue_growth_is_reported_as_a_continuous_rate() {
        let mut evidence = issuer_with_cash_flows(&[100.0, 100.0, 100.0, 100.0]);
        for (step, annual) in evidence.annuals.iter_mut().enumerate() {
            annual.revenue = 1_000.0 * 2.0f64.powi(step as i32);
        }
        // A doubling is ln(2) = 0.6931, not 1.0.
        let growth = evidence.revenue_growth();
        assert!(growth.iter().all(|rate| (rate - 6_931.47).abs() < 1.0));
    }

    /// The newest filing is the least completely tagged one, so the structure
    /// falls back to the newest year that reports every term.
    #[test]
    fn a_latest_year_missing_its_debt_tag_falls_back_rather_than_reading_as_debt_free() {
        let mut evidence = issuer("FB", 0.05, 2.0, 600.0);
        evidence.annuals.last_mut().expect("a history").debt = None;
        assert!(evidence.credit_evidence().is_some());
    }

    /// An implied coupon no lender could charge is a mis-paired reading of two
    /// different obligations, and is refused rather than fitted.
    #[test]
    fn an_impossible_implied_coupon_is_not_credit_evidence() {
        let mut evidence = issuer("BAD", 0.05, 2.0, 600.0);
        for annual in evidence.annuals.iter_mut() {
            annual.debt = Some(annual.interest_expense / 100.0);
        }
        assert!(evidence.credit_evidence().is_none());
    }

    /// A level answers to recent evidence, so history beyond the window cannot
    /// move it.
    #[test]
    fn history_older_than_the_level_window_does_not_move_the_level() {
        let recent = [100.0, 110.0, 120.0, 130.0, 140.0];
        let mut deep = vec![5.0, 6.0, 7.0, 8.0];
        deep.extend_from_slice(&recent);
        let (short, long) = (
            issuer_with_cash_flows(&recent).base_cash_flow(&frame()),
            issuer_with_cash_flows(&deep).base_cash_flow(&frame()),
        );
        assert_eq!(short.value().copied(), long.value().copied());
    }

    #[test]
    fn the_credit_curve_is_fitted_from_the_spreads_the_cohort_actually_pays() {
        let fitted = fit_cross_section(&cohort(), &frame());
        assert!(fitted.credit_curve().is_some());
    }

    /// FR-20 as an invariant of the fit rather than of the model: a cohort whose
    /// spreads do not rise with leverage yields no curve at all.
    #[test]
    fn a_cohort_whose_spreads_fall_with_leverage_yields_no_curve() {
        let inverted = vec![
            issuer("AAA", 0.04, 0.5, 1_240.0),
            issuer("BBB", 0.06, 1.0, 1_030.0),
            issuer("CCC", 0.08, 1.5, 860.0),
            issuer("DDD", 0.10, 2.0, 720.0),
            issuer("EEE", 0.05, 2.5, 600.0),
            issuer("FFF", 0.07, 3.0, 500.0),
        ];
        assert!(fit_cross_section(&inverted, &frame())
            .credit_curve()
            .is_none());
    }

    /// The identity that forces the coverage slope to zero, as a test rather
    /// than a claim in a comment. Whatever the issuer, leverage times coverage
    /// times the observed rate is one, so a fit that used both regressors would
    /// be recovering arithmetic instead of measuring a credit market.
    #[test]
    fn leverage_coverage_and_the_observed_rate_multiply_to_one() {
        let residuals: Vec<f64> = cohort()
            .iter()
            .filter_map(IssuerEvidence::capital_structure)
            .map(|structure| {
                structure.leverage * structure.coverage * structure.effective_rate_bps / 10_000.0
                    - 1.0
            })
            .collect();
        assert!(
            residuals.iter().all(|residual| residual.abs() < 1e-12),
            "the identity did not hold: {residuals:?}"
        );
    }

    #[test]
    fn the_growth_path_is_fitted_across_the_cohort_not_per_issuer() {
        let fitted = fit_cross_section(&cohort(), &frame());
        assert!(fitted.growth_path().is_some());
    }

    /// The whole pipeline, end to end, on evidence with no gaps in it.
    #[test]
    fn a_complete_issuer_publishes_a_posterior() {
        let cohort = cohort();
        let fitted = fit_cross_section(&cohort, &frame());
        assert!(value(&cohort[0], &frame(), &fitted).is_published());
    }

    /// FR-29's consequence, made visible: with no invested capital in evidence
    /// the return is absent and the valuation sits at the value-neutral line,
    /// `cash flow / wacc`, whatever the growth posterior says.
    #[test]
    fn an_absent_return_on_capital_values_at_the_neutral_line() {
        let cohort = cohort();
        let fitted = fit_cross_section(&cohort, &frame());
        let issuer = &cohort[0];
        let discount = *issuer
            .discount_rate(&frame(), &fitted)
            .value()
            .expect("resolved wacc")
            / 10_000.0;
        let cash_flow = *issuer
            .base_cash_flow(&frame())
            .value()
            .expect("resolved cash flow");
        let shares = issuer.shares_outstanding.expect("shares") as f64;
        let expected = (cash_flow / discount - (issuer.total_debt - issuer.total_cash)) / shares;
        let published = median_cents(&value(issuer, &frame(), &fitted)).expect("published");
        assert!(
            (published as f64 / 100.0 - expected).abs() < 0.02,
            "published {published} cents is not the neutral line {expected}"
        );
    }

    /// A financial issuer classifies correctly and then refuses for the input it
    /// lacks, rather than being valued by the model that does not describe it.
    #[test]
    fn a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow() {
        let cohort = cohort();
        let fitted = fit_cross_section(&cohort, &frame());
        let mut bank = cohort[0].clone();
        bank.sector = "Financial Services".to_string();
        bank.industry = "Banks - Diversified".to_string();
        assert_eq!(
            value(&bank, &frame(), &fitted).refusal().map(|r| r.kind()),
            Some("evidence")
        );
    }

    #[test]
    fn an_issuer_with_too_little_history_refuses_rather_than_extrapolating() {
        let cohort = cohort();
        let fitted = fit_cross_section(&cohort, &frame());
        let mut thin = cohort[0].clone();
        thin.annuals.truncate(2);
        assert!(!value(&thin, &frame(), &fitted).is_published());
    }

    /// A hundredfold revenue jump in the middle of a series is not a growth
    /// year, and the cohort's own dispersion says so.
    #[test]
    fn a_planted_extreme_growth_year_is_excluded_from_the_pooled_centre() {
        let contaminated =
            issuer_with_revenues("CON", &[1_000.0, 1_050.0, 100_000.0, 105_000.0, 110_000.0]);
        assert_eq!(
            growth_diagnostics(&cohort_with(contaminated)).growth_pooled_discarded,
            1
        );
    }

    /// And the year it excluded from the centre is excluded from the fit that
    /// uses the centre. One year in the middle of a series belongs to two
    /// consecutive-year transitions, so both of them go.
    #[test]
    fn an_excluded_growth_year_takes_both_of_its_pairs_out_of_the_fit() {
        let contaminated =
            issuer_with_revenues("CON", &[1_000.0, 1_050.0, 100_000.0, 105_000.0, 110_000.0]);
        assert_eq!(
            growth_diagnostics(&cohort_with(contaminated)).growth_pairs_dropped,
            2
        );
    }

    /// The two losses are different quantities and the fit reports both: one
    /// contaminated observation, two pairs of fit evidence gone with it.
    #[test]
    fn the_cost_in_pairs_is_reported_separately_from_the_count_of_bad_years() {
        let contaminated =
            issuer_with_revenues("CON", &[1_000.0, 1_050.0, 100_000.0, 105_000.0, 110_000.0]);
        let diagnostics = growth_diagnostics(&cohort_with(contaminated));
        assert!(
            diagnostics.growth_pooled_discarded != diagnostics.growth_pairs_dropped,
            "one discarded year cost two pairs, so the two counters cannot be the same number: {diagnostics:?}"
        );
    }

    /// A year at the edge of a history belongs to one transition, not two, so
    /// the fit loses one pair. The exclusion is applied to the years, not to a
    /// fixed count of pairs per year.
    #[test]
    fn an_excluded_year_at_the_edge_of_a_series_costs_only_the_one_pair_it_touched() {
        let contaminated = issuer_with_revenues(
            "EDGE",
            &[1_000.0, 100_000.0, 105_000.0, 110_000.0, 115_000.0],
        );
        assert_eq!(
            growth_diagnostics(&cohort_with(contaminated)).growth_pairs_dropped,
            1
        );
    }

    /// An issuer whose every year is a category error contributes nothing to the
    /// fit — and specifically not a pair bridging the hole its exclusions left,
    /// which is what a naive "drop the bad years and re-window" would build.
    #[test]
    fn an_issuer_whose_whole_series_is_excluded_contributes_no_pairs() {
        let unusable = issuer_with_revenues("BAD", &[1.0, 1_000.0, 1_000_000.0, 1.0e9, 1.0e12]);
        assert_eq!(
            growth_diagnostics(&cohort_with(unusable)).growth_pairs,
            growth_diagnostics(&cohort()).growth_pairs
        );
    }

    /// And the cohort still fits: one unusable member is a partial loss of
    /// evidence, not a refusal for everybody.
    #[test]
    fn the_fit_still_resolves_when_one_issuer_is_entirely_excluded() {
        let unusable = issuer_with_revenues("BAD", &[1.0, 1_000.0, 1_000_000.0, 1.0e9, 1.0e12]);
        assert!(fit_cross_section(&cohort_with(unusable), &frame())
            .growth_path()
            .is_some());
    }

    /// An issuer with exactly two transitions, one of them contaminated. Its
    /// surviving year has nothing to pair with, and pairing it across the gap
    /// would invent a transition that never happened.
    #[test]
    fn a_broken_only_pair_is_not_bridged_across_the_gap() {
        let short = issuer_with_revenues("TWO", &[1_000.0, 100_000.0, 105_000.0]);
        assert_eq!(
            growth_diagnostics(&cohort_with(short)).growth_pairs,
            growth_diagnostics(&cohort()).growth_pairs
        );
    }

    /// The change is confined to contaminated evidence: a cohort with nothing
    /// out of place loses nothing.
    #[test]
    fn a_clean_cohort_discards_nothing() {
        assert_eq!(growth_diagnostics(&cohort()).growth_pooled_discarded, 0);
    }

    /// And therefore fits exactly what the plain mean fitted before this
    /// estimator replaced it. The number is the one this cohort produced under
    /// `mean(&series.flatten())`, pinned so that a change in the trimming rule
    /// cannot quietly move an uncontaminated fit.
    #[test]
    fn a_clean_cohort_fits_the_persistence_the_plain_mean_fitted() {
        let persistence = growth_diagnostics(&cohort()).growth_persistence;
        assert!(
            (persistence - PERSISTENCE_BEFORE_WAVE_3).abs() < 1e-12,
            "this cohort now fits {persistence}"
        );
    }

    /// Measured on this cohort under `mean(&series.flatten())`, before the
    /// pooled mean was replaced.
    const PERSISTENCE_BEFORE_WAVE_3: f64 = 0.475_680_263_352_406_93;

    /// The trailing channel's sample size is the number of years behind its
    /// width, not the number of years the issuer filed. Four transitions, one of
    /// them a hundredfold jump, three usable.
    #[test]
    fn the_trailing_channel_counts_the_years_it_kept_not_the_years_it_saw() {
        let contaminated =
            issuer_with_revenues("CON", &[1_000.0, 1_050.0, 1_113.0, 111_300.0, 117_000.0]);
        assert_eq!(
            contaminated
                .growth_posterior(&frame())
                .uncertainty()
                .expect("a measured trailing channel")
                .basis()
                .sample_size(),
            3
        );
    }

    /// The width comes from the same three years the centre came from. Were the
    /// untrimmed variance reported instead, this channel would arrive at `fuse`
    /// about a thousand times wider than the evidence it actually rests on —
    /// a clean level carrying a contaminated precision, which is the failure
    /// this pairing exists to make unrepresentable.
    #[test]
    fn a_contaminated_growth_year_does_not_widen_the_trailing_channel() {
        let contaminated =
            issuer_with_revenues("CON", &[1_000.0, 1_050.0, 1_113.0, 111_300.0, 117_000.0]);
        let growth = contaminated.revenue_growth();
        let untrimmed = sample_variance(&growth).expect("a spread") / growth.len() as f64;
        assert!(
            contaminated
                .growth_posterior(&frame())
                .uncertainty()
                .expect("a measured trailing channel")
                .variance()
                < untrimmed / 100.0,
            "the trailing width is the untrimmed one: {untrimmed}"
        );
    }

    /// A history with no width is not a history known with certainty. Trimming
    /// leaves a set of identical values, a variance of zero, and inverse-variance
    /// weighting would read that as infinite confidence — so the channel is
    /// absent instead.
    #[test]
    fn a_flat_growth_history_leaves_the_trailing_channel_absent() {
        let flat = issuer_with_revenues("FLAT", &[1_000.0, 1_000.0, 1_000.0, 1_000.0, 1_000.0]);
        assert!(flat.growth_posterior(&frame()).value().is_none());
    }

    #[test]
    fn least_squares_recovers_the_coefficients_it_was_given() {
        let observations: Vec<(f64, f64)> = (0..12)
            .map(|step| (step as f64, 3.0 + 2.0 * step as f64))
            .collect();
        let fit = least_squares(&observations).expect("a determined fit");
        assert!((fit.intercept - 3.0).abs() < 1e-9 && (fit.slope - 2.0).abs() < 1e-9);
    }
}
