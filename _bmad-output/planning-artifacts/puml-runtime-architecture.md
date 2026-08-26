---
status: active
feature: PUML runtime
date: 2026-08-26
contract: shared/contracts/puml-runtime-v1.json
first_document: _bmad-output/planning-artifacts/earnings-cheapness.puml
---

# PUML runtime

A diagram-backed model has four types. A new hunt is a new PlantUML document plus a `PumlHost`. It is not a new engine.

## Layers

| Layer | Type | Role |
| --- | --- | --- |
| 1 | `Model` | Common evaluate surface. Code models and PUML models both implement this. |
| 2 | `PumlModel` | A `Model` whose policy is a parsed PlantUML activity document. |
| 3 | `PumlModelFactory` | Pure: PUML text in, `PumlModel` out. No I/O. Fail closed on unknown activity syntax. |
| 4 | `PumlEngine` | Walks the activity graph. Runs formulas that sit in the tree. Named phrases go to `PumlHost`. |

`PumlHost` is a **primitive catalog** (`call(name, args)`). It is not a phrase dictionary of one hunt. The app holds a `Model`.

```
Model.evaluate(input) → ModelOutput
        ▲
   PumlModel (document + identity)
        ▲
   PumlModelFactory.load(source, host)
        uses
   PumlEngine.run(document, input, host)
        phrases
   PumlHost
```

## Evaluate contract

`ModelInput` is a named map of `ModelValue`. Missing is a value. Zero is not absence.

`ModelOutput` is generic:

- `bindings` — every name the run wrote
- `flags` — boolean markers from `:flag Name;`
- `emission` — the last `:Name;` / `:Name(arg);` / `:Name reason=x;` box, or none

The runtime does not own `Act`, `Watch`, `Avoid`, `Unavailable`, or Watch-reason order. Those names live in the document. A host may fill emit fields the diagram leaves empty (Watch reason). A caller that wants hunt triage reads the emission.

## What the factory accepts

The first dialect is activity: `start` / `stop`, `partition`, assignment, `flag`, emit, `if` / `elseif` / `else`, `split`, notes, legend.

An activity box is one of:

| Shape | Step |
| --- | --- |
| `name = expr` or `name ← expr` | Assign |
| `name ← empty` | Clear |
| `flag Name` | Flag |
| PascalCase `Name`, `Name(arg)`, or `Name reason=x` | Emit |
| anything else with no `=` / `←` | BareCall to the host |

A note whose header is `key, first match:` plus numbered rows becomes `document.tables[key]`. That extraction is generic. The engine does not interpret the table. The host may.

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
| English nickname | `alias:` note in the `.puml` | none |
| New primitive name (`ols`, `cheapness`, …) | host.`call` | yes |
| New hunt | `.puml` + host extras | host only |
| New diagram dialect | new `PumlModelFactory` | factory only |

## Forbidden in factory and engine

- Hard-coded hunt labels (`Act`, `Watch`, `Avoid`, `AlignedCheap`, …)
- Hard-coded Watch-reason order or `q < 0` predicates
- `ModelOutput.Decided` / `ModelOutput.Unavailable` as types
- Hunt arithmetic that the diagram states in a phrase (that is host work)
- Silent default to a formula when a phrase is unknown (return `Missing`)

## First document

`earnings-cheapness.puml` title `EarningsCheapness`. Host: `BaratasPumlHost`. Process binding: `BaratasModels`. Tape, Street consensus EPS, and Quant Engine stay out unless a later document names them.
