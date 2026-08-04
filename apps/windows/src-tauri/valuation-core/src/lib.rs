//! The valuation kernel: a pure functional core.
//!
//! # What "pure" means here, concretely
//!
//! Nothing in this crate performs I/O, reads a clock, reaches a network, or
//! observes a market price. Everything it needs arrives as an argument and
//! everything it concludes leaves as a return value. The property is enforced by
//! this crate's dependency list being empty (FR-1) — adding a dependency capable
//! of I/O is the one reviewable event that would break it.
//!
//! The Shell — the existing `discount_screener_windows_lib` crate — fetches,
//! caches, retries, renders, and decides *when* to value something. This crate
//! decides *what a thing is worth given stated evidence*, and nothing else.
//!
//! # Street is not here
//!
//! There is no market price and no analyst price target in this crate, and there
//! is no argument that accepts one (FR-35). Street is a diagnostic computed by
//! the Shell after the fact, against published output. It cannot be a clamp
//! target, an optimand, or an acceptance criterion because it is not reachable
//! from inside the value function.
//!
//! # The specification is the outlines
//!
//! `tests/features/*.feature` is the contract, not documentation of it (FR-45).
//! Behaviour is added by adding a row to an existing `Examples` table; a new
//! `Scenario Outline` requires a manifest entry stating what no existing table
//! covers (FR-44). The manifest and the table schema are themselves enforced by
//! `tests/schema.rs`, so "the tables are the contract" is a checked claim rather
//! than a convention.
//!
//! # Status
//!
//! Under construction. The previous engine remains live in the Shell and is
//! marked deprecated module by module; it is retired only when this core carries
//! the behaviour it is being retired for. Nothing here is wired into the running
//! application yet.

pub mod capital;
pub mod evidence;
pub mod posterior;
pub mod projection;

pub use evidence::{AbsenceReason, Observation, Provenance, Uncertainty, UncertaintyBasis};
pub use capital::{cost_of_debt, wacc, CreditCurve};
pub use posterior::{fuse, Fusion};
pub use projection::{intrinsic_value, GrowthPath};
