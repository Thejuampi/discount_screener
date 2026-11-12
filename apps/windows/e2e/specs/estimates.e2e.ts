import { $, browser, expect } from "@wdio/globals";
import type { IndexEstimatesReport, ScenarioEstimate, ValuationDivergenceAudit } from "../../src/api";
import { startApp } from "../support/backend";

const panel = ".est-panel";
const analysts = "[data-estimates-analysts]";
const priceGap = "[data-estimates-price-gap]";
const range = "[data-estimates-range]";
const timedReturn = "[data-estimates-timed-return]";
const coverage = "[data-estimates-dcf-coverage]";
const low = '[data-estimates-endpoint="low"]';
const high = '[data-estimates-endpoint="high"]';

const dcfScenarios: ScenarioEstimate[] = [
  { scenario: "bear_dcf", weighted_price_cents: 17_000, coverage_count: 4, implied_upside_bps: -3_000 },
  { scenario: "base_dcf", weighted_price_cents: 38_000, coverage_count: 4, implied_upside_bps: 8_000 },
  { scenario: "bull_dcf", weighted_price_cents: 54_000, coverage_count: 4, implied_upside_bps: 16_000 },
];

const report = {
  profile_name: "qa",
  current_weighted_price_cents: 20_000,
  total_symbols: 4,
  computed_at_epoch_seconds: 1_770_681_600,
  dcf_coverage: {
    total_eligible_symbols: 4,
    covered_symbols: 4,
    coverage_bps: 10_000,
    status: "ready",
  },
} satisfies Omit<IndexEstimatesReport, "scenarios">;

const audit: ValuationDivergenceAudit = {
  profile_name: "qa",
  model_policy_version: "e2e-policy",
  computed_at_epoch_seconds: 1_770_681_600,
  candidate_count: 1,
  comparable_count: 0,
  unavailable_count: 1,
  rows: [],
  unavailable: [{
    symbol: "AAPL",
    analyst_anchor_available: true,
    dcf_available: false,
    reason: "Insufficient model inputs",
  }],
};

function endpoint(scenario: "analyst_low" | "analyst_high", priceGapBps: number, count: number): ScenarioEstimate {
  return {
    scenario,
    weighted_price_cents: count > 0 ? 20_000 : 0,
    coverage_count: count,
    implied_upside_bps: priceGapBps,
  };
}

async function boot(...analystScenarios: ScenarioEstimate[]): Promise<void> {
  await browser.tauri.restoreAllMocks();
  await browser.url("/e2e.html");
  const estimates = await browser.tauri.mock("get_index_estimates");
  await estimates.mockResolvedValue({ ...report, scenarios: [...dcfScenarios, ...analystScenarios] });
  const auditCommand = await browser.tauri.mock("run_qa_valuation_divergence_audit");
  await auditCommand.mockResolvedValue(audit);
  await startApp("ready", "estimates", false, {
    trackedFeed: { profile: "qa", symbols: ["AAPL", "META", "MSFT", "NVDA"], editable: false },
  });
}

describe("Estimates", () => {
  it("shows covered analyst gaps without the DCF projection or a timed return", async () => {
    await boot(endpoint("analyst_low", -1_250, 2), endpoint("analyst_high", 2_550, 3));

    await expect($(analysts)).toBeDisplayed();
    await expect($(priceGap)).toHaveText(expect.stringContaining("Price gap"));
    await expect($(range)).toBeDisplayed();
    await expect($(low)).toHaveText(expect.stringContaining("-12.5%"));
    await expect($(low)).toHaveText(expect.stringContaining("2 companies with targets"));
    await expect($(high)).toHaveText(expect.stringContaining("+25.5%"));
    await expect($(high)).toHaveText(expect.stringContaining("3 companies with targets"));
    await expect($(timedReturn)).toHaveText(expect.stringContaining("Timed return"));
    await expect($(timedReturn)).toHaveText(expect.stringContaining("Unavailable. Target date is missing."));
    await expect($(timedReturn)).not.toHaveText(expect.stringContaining("%"));
    await expect($(coverage)).toBeDisplayed();

    await expect($(panel)).not.toHaveText(expect.stringContaining("Base upside"));
    await expect($(panel)).not.toHaveText(expect.stringContaining("Internal DCF model"));
    await expect($(panel)).not.toHaveText(expect.stringContaining("+80.0%"));
    await expect($(panel)).not.toHaveText(expect.stringContaining("$540.00"));

    await $("button=Run QA audit (max 20)").click();
    await expect($(panel)).toHaveText(expect.stringContaining("Insufficient model inputs"));
    await expect($(range)).toBeDisplayed();
  });

  it("shows a genuinely covered zero gap and withholds the missing endpoint", async () => {
    await boot(endpoint("analyst_low", 0, 1), endpoint("analyst_high", 0, 0));

    await expect($(priceGap)).toBeDisplayed();
    await expect($(range)).not.toExist();
    await expect($(low)).toHaveText(expect.stringContaining("0.0%"));
    await expect($(high)).toHaveText(expect.stringContaining("Unavailable"));
    await expect($(high)).not.toHaveText(expect.stringContaining("%"));
    await expect($(timedReturn)).toHaveText(expect.stringContaining("Unavailable. Target date is missing."));
  });

  it("shows the missing-coverage state instead of a zero analyst range", async () => {
    await boot(endpoint("analyst_low", 0, 0), endpoint("analyst_high", 0, 0));

    await expect($(priceGap)).toHaveText(expect.stringContaining("No analyst target coverage."));
    await expect($(range)).not.toExist();
    await expect($(analysts)).not.toHaveText(expect.stringContaining("0.0%"));
    await expect($(timedReturn)).toHaveText(expect.stringContaining("Unavailable. Target date is missing."));
  });
});
