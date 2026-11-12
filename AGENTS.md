# Discount Screener Agent Guide

## Product Principles

- Build a personal investment research workstation for Juan. Warren Buffett's investing philosophy is the main inspiration.
- Require evidence of profitability before a company can qualify as a Long investment candidate.
- Apply eligibility before ranking. Scores, analyst targets, and price momentum cannot override the profitability requirement.
- Keep companies with losses or missing evidence available for research. Show those two states separately.
- Show the source, date, and uncertainty of material evidence. Keep market price, analyst targets, and model value distinct.
- Limit the product to research and analysis. Add multi-user features only at Juan's request.

## Working Rules

- Read the nearest `AGENTS.md` before editing an application. Use the [documentation index](docs/index.md) to find its guides.
- Use shared contracts first, then current documentation, then the scoped guide, then this file. Use the archive for history.
- Report a product gap when shipped behavior conflicts with a product principle.
- Keep business logic in its owning module. Keep UI passive and separate rendering, persistence, and network access.
- Preserve fixed-point values. Use typed states for unavailable, stale, disputed, and provisional evidence.
- Keep expensive work bounded and demand-driven. Preserve user data and unrelated changes.
- Use strict TDD for behavior changes and verify the normal entry point. Follow the [validation guide](docs/operations/change-validation.md).
- Before a broad port, show Juan the feature inventory, intentional differences, and proposed scope.
- Read the [valuation rules](docs/architecture/valuation-model-family.md) and [source rules](docs/architecture/dcf-source-consistency.md) before related changes.
- Run live QA in the `qa` universe after Juan authorizes it. Keep secrets, databases, build products, and agent state out of Git.
- Update current documentation for behavior changes. Keep one current home for each rule.
- Give the result first. Use short, active sentences and ASD-STE100 in technical English.
