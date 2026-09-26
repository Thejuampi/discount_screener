"""Checks for the dated Oracle valuation sensitivity case."""

import unittest
from dataclasses import replace
from decimal import Decimal

from valuation_case import FY2026, FY2027_Q1, Scenario, oracle_sensitivity, value_per_share


class FilingEvidenceTest(unittest.TestCase):
    def test_reported_fcf_matches_oracle(self):
        self.assertEqual(FY2026.operating_cash_m - FY2026.capex_m, Decimal("-23686"))
        self.assertEqual(FY2027_Q1.operating_cash_m - FY2027_Q1.capex_m, Decimal("-5396"))

    def test_customer_advance_is_visible(self):
        self.assertEqual(FY2027_Q1.customer_financing_prepayments_m, Decimal("11363"))
        self.assertEqual(
            FY2027_Q1.operating_cash_m - FY2027_Q1.customer_financing_prepayments_m,
            Decimal("11740"),
        )


class ValuationCaseTest(unittest.TestCase):
    def test_lower_fcff_lowers_value(self):
        reference = Scenario(annual_revenue_m=(Decimal("90000"),) * 5,
                             annual_fcff_m=(Decimal("10000"),) * 5)
        costlier = Scenario(annual_revenue_m=reference.annual_revenue_m,
                           annual_fcff_m=(Decimal("9000"),) * 5)
        self.assertLess(value_per_share(costlier), value_per_share(reference))

    def test_unfunded_equity_is_unavailable(self):
        case = Scenario(annual_revenue_m=(Decimal("1"),) * 5,
                        annual_fcff_m=(Decimal("-100"),) * 5)
        self.assertIsNone(value_per_share(case))

    def test_terminal_growth_must_be_below_discount_rate(self):
        case = Scenario(annual_revenue_m=(Decimal("90"),) * 5,
                        annual_fcff_m=(Decimal("10"),) * 5,
                        stable_growth=Decimal("0.12"))
        self.assertIsNone(value_per_share(case))

    def test_rate_boundary_is_strict(self):
        case = Scenario(annual_revenue_m=(Decimal("90000"),) * 5,
                        annual_fcff_m=(Decimal("10000"),) * 5,
                        stable_growth=Decimal("0.1141"))
        self.assertIsNone(value_per_share(case))
        valid = replace(case, stable_growth=Decimal("0.1140"))
        self.assertIsNotNone(value_per_share(valid))

    def test_higher_fy27_capex_lowers_oracle_value(self):
        floor = oracle_sensitivity(Decimal("0.26"), 2033)
        higher = oracle_sensitivity(Decimal("0.26"), 2033, fy27_capex_m=Decimal("75000"))
        self.assertLess(value_per_share(higher), value_per_share(floor))
        self.assertLess(higher.annual_fcff_m[0], floor.annual_fcff_m[0])

    def test_higher_share_count_lowers_oracle_value(self):
        baseline = oracle_sensitivity(Decimal("0.26"), 2033)
        diluted = oracle_sensitivity(Decimal("0.26"), 2033, shares_m=Decimal("3175.2"))
        self.assertLess(value_per_share(diluted), value_per_share(baseline))

    def test_invalid_fy27_capex_is_rejected(self):
        with self.assertRaises(ValueError):
            oracle_sensitivity(Decimal("0.26"), 2033, fy27_capex_m=Decimal("-1"))

    def test_august_balance_sheet_excludes_reported_first_quarter_cash_flow(self):
        case = oracle_sensitivity(Decimal("0.26"), 2033)
        tax = Decimal("0.21")
        full_year_fcff = (
            case.annual_revenue_m[0]
            * (FY2026.operating_cash_m - FY2026.customer_financing_prepayments_m)
            / Decimal("67357")
            + Decimal("4599") * (Decimal(1) - tax)
            - FY2026.capex_m
        )
        actual_first_quarter_fcff = (
            FY2027_Q1.operating_cash_m
            - FY2027_Q1.customer_financing_prepayments_m
            + Decimal("1428") * (Decimal(1) - tax)
            - FY2027_Q1.capex_m
        )
        self.assertEqual(case.annual_fcff_m[0], full_year_fcff - actual_first_quarter_fcff)
        self.assertEqual(case.first_period_years, Decimal("0.75"))

    def test_shorter_first_period_changes_present_value(self):
        full_year = Scenario(annual_revenue_m=(Decimal("90000"),) * 5,
                             annual_fcff_m=(Decimal("10000"),) * 5)
        shorter = Scenario(annual_revenue_m=full_year.annual_revenue_m,
                           annual_fcff_m=full_year.annual_fcff_m,
                           first_period_years=Decimal("0.75"))
        self.assertGreater(value_per_share(shorter), value_per_share(full_year))

    def test_first_period_boundary_is_strict(self):
        case = Scenario(annual_revenue_m=(Decimal("90000"),) * 5,
                        annual_fcff_m=(Decimal("10000"),) * 5)
        self.assertIsNone(value_per_share(replace(case, first_period_years=Decimal(0))))
        self.assertIsNotNone(value_per_share(replace(case, first_period_years=Decimal("0.01"))))
        self.assertIsNotNone(value_per_share(replace(case, first_period_years=Decimal(1))))
        self.assertIsNone(value_per_share(replace(case, first_period_years=Decimal("1.01"))))

    def test_customer_financing_cash_is_not_free_equity_cash(self):
        case = oracle_sensitivity(Decimal("0.26"), 2033)
        self.assertEqual(case.customer_financing_liability_m, Decimal("11363"))
        without_obligation = replace(case, customer_financing_liability_m=Decimal(0))
        self.assertLess(value_per_share(case), value_per_share(without_obligation))


if __name__ == "__main__":
    unittest.main()
