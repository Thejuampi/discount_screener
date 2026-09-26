"""Dated ORCL buildout sensitivity. This module does not supply an app valuation.

Official evidence:
https://investor.oracle.com/investor-news/news-details/2026/Oracle-Announces-Record-Q4-and-FY-2026-Results-Driven-by-Cloud-Infrastructure--Cloud-Applications/
https://www.sec.gov/Archives/edgar/data/1341439/000119312526389274/orcl-20260831.htm
https://www.oracle.com/a/ocom/docs/corporate/financial-analyst-meeting-2025-magouyrk.pdf

Money inputs use USD millions. Shares use millions. Scenario assumptions are
explicit and do not claim a verified intrinsic value.
"""

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP


@dataclass(frozen=True)
class FilingEvidence:
    operating_cash_m: Decimal
    capex_m: Decimal
    customer_financing_prepayments_m: Decimal


FY2026 = FilingEvidence(Decimal("31977"), Decimal("55663"), Decimal("4592"))
FY2027_Q1 = FilingEvidence(Decimal("23103"), Decimal("28499"), Decimal("11363"))
FY2026_REVENUE_M = Decimal("67357")
FY2026_INTEREST_M = Decimal("4599")
FY2027_Q1_INTEREST_M = Decimal("1428")
ASSUMED_TAX_RATE = Decimal("0.21")


@dataclass(frozen=True)
class Scenario:
    annual_revenue_m: tuple[Decimal, ...]
    annual_fcff_m: tuple[Decimal, ...]
    first_period_years: Decimal = Decimal(1)
    stable_fcff_margin: Decimal = Decimal("0.26")
    discount_rate: Decimal = Decimal("0.1141")
    stable_growth: Decimal = Decimal("0.025")
    net_debt_m: Decimal = Decimal("88968")
    preferred_m: Decimal = Decimal("4954")
    customer_financing_liability_m: Decimal = Decimal(0)
    shares_m: Decimal = Decimal("3024")


def value_per_share(case: Scenario) -> Decimal | None:
    """Discount FCFF, subtract current claims, then divide by current shares."""
    if (
        not case.annual_revenue_m
        or len(case.annual_revenue_m) != len(case.annual_fcff_m)
        or any(revenue <= 0 for revenue in case.annual_revenue_m)
        or case.shares_m <= 0
        or not Decimal(0) < case.first_period_years <= Decimal(1)
        or case.discount_rate <= case.stable_growth
        or case.stable_fcff_margin <= 0
    ):
        return None
    one_plus_rate = Decimal(1) + case.discount_rate
    present_value_m = sum(
        cash / (one_plus_rate ** (case.first_period_years + year))
        for year, cash in enumerate(case.annual_fcff_m)
    )
    terminal_year = case.first_period_years + len(case.annual_fcff_m) - 1
    terminal_fcff_m = (
        case.annual_revenue_m[-1]
        * (Decimal(1) + case.stable_growth)
        * case.stable_fcff_margin
    )
    terminal_value_m = terminal_fcff_m / (case.discount_rate - case.stable_growth)
    equity_m = (
        present_value_m
        + terminal_value_m / (one_plus_rate ** terminal_year)
        - case.net_debt_m
        - case.preferred_m
        - case.customer_financing_liability_m
    )
    if equity_m <= 0:
        return None
    return (equity_m / case.shares_m).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def oracle_revenue_path(recovery_year: int) -> tuple[Decimal, ...]:
    """FY27 guidance, then FY28-FY30 OCI plan plus flat other revenue.

    Later growth (8% through FY33, 4% after) is an illustrative assumption.
    """
    if recovery_year < 2030:
        raise ValueError("recovery year precedes the published OCI plan")
    revenue = [Decimal("90000"), Decimal("131000"), Decimal("172000"), Decimal("202000")]
    for year in range(2031, recovery_year + 1):
        growth = Decimal("1.08") if year <= 2033 else Decimal("1.04")
        revenue.append(revenue[-1] * growth)
    return tuple(revenue)


def oracle_sensitivity(
    stable_margin: Decimal,
    recovery_year: int,
    *,
    fy27_capex_m: Decimal = FY2026.capex_m,
    shares_m: Decimal = Decimal("3024"),
    discount_rate: Decimal = Decimal("0.1141"),
) -> Scenario:
    """Use FY26 adjusted OCF margin and an explicit FY27 CapEx assumption.

    Oracle expects FY27 CapEx above FY26. Equality gives this scenario a
    favorable FY27 cash-flow assumption. The later margin bridge is hypothetical.
    """
    if fy27_capex_m < 0:
        raise ValueError("FY27 CapEx must be nonnegative")
    revenue = oracle_revenue_path(recovery_year)
    adjusted_ocf_margin = (
        FY2026.operating_cash_m - FY2026.customer_financing_prepayments_m
    ) / FY2026_REVENUE_M
    projected_full_year_fcff_m = (
        revenue[0] * adjusted_ocf_margin
        + FY2026_INTEREST_M * (Decimal(1) - ASSUMED_TAX_RATE)
        - fy27_capex_m
    )
    reported_q1_fcff_m = (
        FY2027_Q1.operating_cash_m
        - FY2027_Q1.customer_financing_prepayments_m
        + FY2027_Q1_INTEREST_M * (Decimal(1) - ASSUMED_TAX_RATE)
        - FY2027_Q1.capex_m
    )
    first_margin = projected_full_year_fcff_m / revenue[0]
    years = len(revenue)
    margins = tuple(
        first_margin + (stable_margin - first_margin) * Decimal(index) / Decimal(years - 1)
        for index in range(years)
    )
    fcff = (projected_full_year_fcff_m - reported_q1_fcff_m,) + tuple(
        revenue[index] * margins[index] for index in range(1, years)
    )
    return Scenario(
        revenue,
        fcff,
        first_period_years=Decimal("0.75"),
        stable_fcff_margin=stable_margin,
        discount_rate=discount_rate,
        customer_financing_liability_m=FY2027_Q1.customer_financing_prepayments_m,
        shares_m=shares_m,
    )


def print_sensitivity() -> None:
    print("ORCL, as of 2026-08-31; USD/share; illustrative cases only")
    print("FY27 CapEx floor is FY26 CapEx. Oracle expects a higher FY27 amount.")
    print("FY27 CapEx | Recovery year | Stable FCFF margin | Conditional value")
    for year in (2030, 2033, 2036):
        for margin in (Decimal("0.20"), Decimal("0.26"), Decimal("0.32")):
            value = value_per_share(oracle_sensitivity(margin, year))
            print(
                f"$55.7B floor | {year} | {margin * 100:.0f}% | "
                f"{f'${value}' if value else 'unavailable'}"
            )
    print("FY27 CapEx | Recovery year | Stable FCFF margin | Conditional value")
    for capex in (Decimal("75000"), Decimal("100000"), Decimal("113996")):
        value = value_per_share(oracle_sensitivity(Decimal("0.26"), 2033, fy27_capex_m=capex))
        print(f"${capex / 1000:.1f}B | 2033 | 26% | {f'${value}' if value else 'unavailable'}")
    print("11.41% WACC is provisional. The terminal margin, path, and CapEx are unverified.")


if __name__ == "__main__":
    print_sensitivity()
