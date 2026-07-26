# UI Inspect → agent-ready clipboard refs

## How to use

1. **Right-click** any instrumented card / panel / row.
2. Toast confirms: `UI ref copied · …`
3. Paste into the agent chat.

That’s it. Left-click still opens symbols and selects rows as usual.

Hover shows a light dashed outline on copyable regions so you know what works.

## Payload

```text
```ds-ui-ref v1
## What
id: dashboard.v2.planCard
...

## Where (construction)
component: apps/windows/src/components/DashboardV2Panel.tsx
region: PlanCard
related:
  - apps/windows/src/conditionalPlan.ts#buildConditionalPlan
...

## Data sources
note: Tauri invoke commands (not HTTP). Open client/impl paths; probe is a recipe for api.ts.
- id: opportunities
  kind: tauri
  role: primary
  command: get_opportunities
  client: apps/windows/src/api.ts#getOpportunities
  impl: apps/windows/src-tauri/src/commands.rs#get_opportunities
  domain:
    - apps/windows/src-tauri/src/engine.rs
    ...
  args: {}
  probe: api.getOpportunities()
  note: Full board rows; filter/match Runtime.symbol when present
- id: conditionalPlan
  kind: client
  role: enrich
  client: apps/windows/src/conditionalPlan.ts#buildConditionalPlan
  note: Pure transform of OpportunityRow → stance/headline; no extra invoke

## Runtime (safe snapshot)
symbol: AAPL
...

## Agent hints
1. ...
```
```

### Data sources section

Windows data is **Tauri `invoke`**, not REST. Each entry tells the agent:

| Field | Meaning |
|---|---|
| `kind` | `tauri` (backend command), `client` (pure frontend), or `upstream` (reserved) |
| `command` | Tauri command string, e.g. `get_symbol_detail` |
| `client` | Path to the `api.ts` wrapper |
| `impl` | Path to the Rust handler |
| `domain` | Scoring / domain modules behind the handler |
| `args` | Runtime-filled invoke args from the snapshot (never invented) |
| `match` | Client-side filter for **list** endpoints (e.g. `get_opportunities` → `{symbol:"MA"}`) |
| `probe` | TypeScript recipe, e.g. `api.getNews("TEL")` or `api.getOpportunities() /* find symbol==="MA" */` — not a URL |

Shared catalog: `apps/windows/src/uiInspect/dataSources.ts` (`DS.*`).

## Implementation

| Piece | Path |
|---|---|
| Framework | `apps/windows/src/uiInspect/` |
| UI catalog | `apps/windows/src/uiInspect/sources.ts` |
| Data sources catalog | `apps/windows/src/uiInspect/dataSources.ts` |
| Payload builder | `apps/windows/src/uiInspect/buildPayload.ts` |
| Wrapper | `UiInspectable` |
| Right-click handler | `UiInspectRoot` |
| Tests | `apps/windows/tests/uiInspect.test.ts` |

To instrument a new visual:

```tsx
import { UI, UiInspectable } from "../uiInspect";

<UiInspectable source={UI.someId} snapshot={{ symbol, score }}>
  ...
</UiInspectable>
```

When adding a new source id in `sources.ts`, attach `dataSources: [DS....]` so the ref lists the real backend path. Add a new `DS` entry if the command is not already catalogued.
