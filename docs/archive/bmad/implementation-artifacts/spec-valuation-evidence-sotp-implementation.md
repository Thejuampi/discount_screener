# SPEC-valuation-evidence-sotp — implementation notes

The shared contract is [`shared/contracts/valuation-evidence-sotp.json`](../../shared/contracts/valuation-evidence-sotp.json). It is the gate for the first executable slice of the SPEC:

- `EvidenceObservation` carries economic period, knowledge/publication time, revision lineage, source vintage, retrieval time, units, source regime, definition, location, extraction method, and quality.
- The point-in-time resolver admits only facts known and retrieved by the decision time, selects the latest eligible revision, and records later revisions/retrieval failures in the replay trace and fingerprint.
- Windows and Android use the same fixed-point public fields and contract goldens for routing, SOTP consolidation, metadata, and exact fingerprints. A one-cent mutation is explicitly rejected.
- Component engines are evidence-specific: operating FCFF/WACC, financial-services residual income, finite resource production, contracted infrastructure exposure, and regulated utility rate-base economics. IFRS and unsupported source regimes refuse until a native normalizer exists.
- SOTP components emit enterprise value only. Corporate overhead is a negative evidenced component; debt, NCI, preferred claims, senior claims, and non-consolidated investments are bridged once at issuer level. Unresolved material items yield `covered_ev_only` and no intrinsic price or valuation score.
- Historical validation requires point-in-time membership evidence and reports later driver accuracy separately from market-outcome diagnostics.
- Desktop classifies the new resource/infrastructure/utility routes but refuses them with `desktop_surface_unsupported`; it does not silently reuse generic FCFF.

Provider ingestion and installed-app live QA remain separate gates. No live provider payloads are fabricated by this pure-domain slice; adapters must populate the contract before a family can publish a valuation. Windows live QA, when wired to a UI path, must use the locked `qa` profile.
