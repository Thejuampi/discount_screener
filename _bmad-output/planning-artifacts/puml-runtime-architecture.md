---
status: active
feature: PUML runtime
date: 2026-08-26
contract: shared/contracts/puml-runtime-v1.json
first_document: _bmad-output/planning-artifacts/earnings-cheapness.puml
---

# PUML runtime

A diagram-backed model has four types plus a catalog. The `.puml` is the live model. Hunt functions live in that same file as callable partitions. The Kotlin lib (`StandardPumlHost`) is included at load. A new hunt formula, if, coefficient, or block is an edit to the `.puml`.

## Layers

| Layer | Type | Role |
| --- | --- | --- |
| 1 | `Model` | Common evaluate surface. Code models and PUML models both implement this. |
| 2 | `PumlModel` | A `Model` whose policy is a parsed PlantUML activity document. |
| 3 | `PumlModelFactory` | Syntax: PUML text in, flow graph out. No I/O. Fail closed on unknown activity syntax. |
| 4 | `PumlEngine` | Walks the flow graph. A call hits the catalog. |
| Catalog | document function, then host | `partition "name(params)"` in the same `.puml`, else a Kotlin primitive. |

`PumlHost` is the Kotlin lib. Reverse-engineered from V2–V5 plus the hunt. The process includes it at `load(source, host)`. Hunt identities stay in the document.

| Name | Input | Source |
| --- | --- | --- |
| `count` | series | length |
| `robust_mean` | issuer annual series | V4 FCF centre, hunt `eps_basis` |
| `median` | small sample / cross-section | V4 panel and agreement, hunt sector |
| `ols` | series vs index time | hunt slope `b` |
| `ramp` | scalar, lo, hi | V2–V5 `smoothRamp`, onto [-1, +1] |
| `clamp` | scalar, lo, hi | coerce used across engines |
| `min` / `max` | scalars | bounds |
| `sign` | scalar | V4 growth conflict, hunt `own_vs_sector` |
| `percentile` | series, value | regime / own-history |
| `foreign` | value, series | MAD outlier, same scale as `robust_mean` |
| `classify` | sector, industry | closed-world `FinancialClassPolicy` |

```
Model.evaluate(input) → ModelOutput
        ▲
   PumlModel (document + identity)
        ▲
   PumlModelFactory.load(source, StandardPumlHost)
        uses
   PumlEngine.run(document, input, host)
        catalog
   document.functions  then  PumlHost
```

## Evaluate contract

`ModelInput` is a named map of `ModelValue`. Missing is a value. Zero is not absence.

`ModelOutput` is generic:

- `bindings` — every name the run wrote
- `flags` — boolean markers from `:flag Name;`
- `emission` — the last `:Name;` / `:Name(arg);` / `:Name reason=x;` box, or none

The runtime does not own `Act`, `Watch`, `Avoid`, `Unavailable`, or Watch-reason order. Those names live in the document. A caller that wants hunt triage reads the emission.

## What the factory accepts

The first dialect is activity: `start` / `stop`, `partition`, assignment, `flag`, emit, `if` / `elseif` / `else`, `split`, notes, legend.

A partition titled `name(param, param)` is a **function**. The engine does not walk it as main flow. A call `name(args)` binds params by position, runs the body in a nested env, and returns the last assignment. Locals do not leak. The hunt and its functions live in one `.puml`.

An activity box is one of:

| Shape | Step |
| --- | --- |
| `name = expr` or `name ← expr` | Assign |
| `name ← empty` | Clear |
| `flag Name` | Flag |
| PascalCase `Name`, `Name(arg)`, or `Name reason=x` | Emit |
| anything else with no `=` / `←` | BareCall to the host |

Unknown activity syntax fails closed. Sequence, class, and other PlantUML dialects are later factories. They still produce a `PumlModel`.

## Load rule

A process loads each PUML source once at start. A restart sees edits. Mid-run reload is out of scope.

## Extensibility

A diagram edit is a model edit. Restart the process. Do not change Kotlin.

| Change | Edit | Kotlin |
| --- | --- | --- |
| New partition / block | the `.puml` | none |
| New `if` / `elseif` | the `.puml` | none |
| Coefficient (`×2` → `×3`, `0.7` → `0.5`) | the `.puml` | none |
| Hunt formula (ramp, clip, identity) | a function partition in the `.puml` | none |
| New named function | `partition "name(params)"` in the same `.puml` | none |
| New primitive name (`ols`, `classify`, …) | Kotlin lib `host.call` | yes |
| New hunt | a new `.puml` | none if the primitives already exist |
| New diagram dialect | new `PumlModelFactory` | factory only |

## Forbidden in factory, engine, and host

- Hard-coded hunt labels (`Act`, `Watch`, `Avoid`, `AlignedCheap`, …)
- Hard-coded Watch-reason order or `q < 0` predicates
- `ModelOutput.Decided` / `ModelOutput.Unavailable` as types
- Hunt arithmetic in Kotlin (`cheapness()`, aliases, first-match tables)
- English nickname maps in notes
- Silent default to a formula when a phrase is unknown (return `Missing`)

## First document

`earnings-cheapness.puml` title `EarningsCheapness`. Host: `StandardPumlHost`. Process binding: `BaratasModels`. Tape, Street consensus EPS, and Quant Engine stay out unless a later document names them.
