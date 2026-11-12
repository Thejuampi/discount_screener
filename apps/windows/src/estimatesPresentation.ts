import type { ScenarioEstimate } from "./api";

export interface AnalystPriceGap {
  priceGapBps: number;
  coverageCount: number;
}

function coveredGap(scenario: ScenarioEstimate | undefined): AnalystPriceGap | null {
  if (!scenario || !Number.isSafeInteger(scenario.coverage_count) || scenario.coverage_count <= 0
    || !Number.isSafeInteger(scenario.implied_upside_bps)) return null;

  return {
    priceGapBps: scenario.implied_upside_bps,
    coverageCount: scenario.coverage_count,
  };
}

export function analystPriceGaps(scenarios: readonly ScenarioEstimate[]) {
  return {
    low: coveredGap(scenarios.find((scenario) => scenario.scenario === "analyst_low")),
    high: coveredGap(scenarios.find((scenario) => scenario.scenario === "analyst_high")),
  };
}
