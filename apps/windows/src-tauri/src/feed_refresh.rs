use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RefreshPhase {
    #[default]
    Idle,
    Startup,
    Retrying,
    Queued,
    Refreshing,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RefreshTrigger {
    Startup,
    Manual,
    Scheduled,
}

/// The status bar reads the pass progress and unresolved issues for this profile.
#[derive(Clone, Copy, Debug, Default, serde::Serialize)]
pub struct RefreshProgress {
    pub phase: RefreshPhase,
    pub trigger: Option<RefreshTrigger>,
    pub processed: usize,
    pub total: usize,
    pub quote_failures: usize,
    pub chart_failures: usize,
    pub rate_limited: usize,
}

#[derive(Clone, Copy, Default)]
pub(crate) struct AttemptIssues {
    pub quote: InputAttempt,
    pub chart: InputAttempt,
    pub rate_limited: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum InputAttempt {
    #[default]
    Skipped,
    Ready,
    Unavailable,
}

impl InputAttempt {
    fn update_failure(self, previous: bool) -> bool {
        match self {
            Self::Skipped => previous,
            Self::Ready => false,
            Self::Unavailable => true,
        }
    }
}

impl AttemptIssues {
    pub(crate) fn rate_limited() -> Self {
        Self {
            rate_limited: true,
            ..Self::default()
        }
    }
}

#[derive(Clone, Copy, Default)]
struct ActiveIssues {
    quote_failed: bool,
    chart_failed: bool,
    rate_limited: bool,
}

/// The feed-status mutex owns the pass claim and all progress writes.
#[derive(Default)]
pub(crate) struct FeedRefresh {
    progress: RefreshProgress,
    attempted: HashSet<String>,
    issues: HashMap<String, ActiveIssues>,
    legacy_retry_error: Option<String>,
}

impl FeedRefresh {
    fn start_pass(&mut self, phase: RefreshPhase, trigger: RefreshTrigger, total: usize) {
        self.progress.phase = phase;
        self.progress.trigger = Some(trigger);
        self.progress.total = total;
        self.progress.processed = 0;
        self.attempted.clear();
        self.legacy_retry_error = None;
    }

    pub(crate) fn startup(total: usize) -> Self {
        let mut refresh = Self::default();
        refresh.start_pass(RefreshPhase::Startup, RefreshTrigger::Startup, total);
        refresh
    }

    pub(crate) fn failed_symbols(&self) -> HashSet<String> {
        self.issues.keys().cloned().collect()
    }

    pub(crate) fn restart_for_membership(&mut self, active: &HashSet<String>, pending: usize) {
        self.issues.retain(|symbol, _| active.contains(symbol));
        self.progress = RefreshProgress {
            quote_failures: self
                .issues
                .values()
                .filter(|issue| issue.quote_failed)
                .count(),
            chart_failures: self
                .issues
                .values()
                .filter(|issue| issue.chart_failed)
                .count(),
            rate_limited: self
                .issues
                .values()
                .filter(|issue| issue.rate_limited)
                .count(),
            ..RefreshProgress::default()
        };
        self.attempted.clear();
        self.legacy_retry_error = None;
        if !active.is_empty() {
            self.start_pass(RefreshPhase::Startup, RefreshTrigger::Startup, pending);
        }
    }

    pub(crate) fn progress(&self) -> RefreshProgress {
        self.progress
    }

    pub(crate) fn queue_manual(&mut self, total: usize) -> bool {
        if self.progress.phase != RefreshPhase::Idle {
            return false;
        }
        self.start_pass(RefreshPhase::Queued, RefreshTrigger::Manual, total);
        true
    }

    pub(crate) fn begin_recurring(&mut self, total: usize) -> bool {
        let trigger = match self.progress.phase {
            RefreshPhase::Idle => RefreshTrigger::Scheduled,
            RefreshPhase::Queued => RefreshTrigger::Manual,
            _ => return false,
        };
        self.start_pass(RefreshPhase::Refreshing, trigger, total);
        true
    }

    pub(crate) fn retrying(&mut self, legacy_error: String) {
        self.progress.phase = RefreshPhase::Retrying;
        self.legacy_retry_error = Some(legacy_error);
    }

    pub(crate) fn record(&mut self, symbol: &str, attempt: AttemptIssues) {
        let previous = self.issues.remove(symbol).unwrap_or_default();
        let active = ActiveIssues {
            quote_failed: attempt.quote.update_failure(previous.quote_failed),
            chart_failed: attempt.chart.update_failure(previous.chart_failed),
            rate_limited: attempt.rate_limited,
        };
        self.progress.quote_failures = self.progress.quote_failures
            - usize::from(previous.quote_failed)
            + usize::from(active.quote_failed);
        self.progress.chart_failures = self.progress.chart_failures
            - usize::from(previous.chart_failed)
            + usize::from(active.chart_failed);
        self.progress.rate_limited = self.progress.rate_limited
            - usize::from(previous.rate_limited)
            + usize::from(active.rate_limited);
        if active.quote_failed || active.chart_failed || active.rate_limited {
            self.issues.insert(symbol.to_owned(), active);
        }
        self.attempted.insert(symbol.to_owned());
        self.progress.processed = self.attempted.len();
    }

    pub(crate) fn finish(&mut self, legacy_error: Option<String>) {
        self.progress.phase = RefreshPhase::Idle;
        self.attempted.clear();
        self.legacy_retry_error = legacy_error;
    }

    pub(crate) fn legacy_error(&self) -> Option<String> {
        if let Some(error) = &self.legacy_retry_error {
            return Some(error.clone());
        }
        let RefreshProgress {
            quote_failures,
            chart_failures,
            rate_limited,
            ..
        } = self.progress;
        if quote_failures == 0 && chart_failures == 0 && rate_limited == 0 {
            return None;
        }
        Some(format!(
            "Feed: {quote_failures} quote, {chart_failures} chart, {rate_limited} rate limited"
        ))
    }
}
