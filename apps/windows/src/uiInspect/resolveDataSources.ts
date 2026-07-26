import type {
  ResolvedDataSource,
  UiAppContext,
  UiDataSource,
  UiSnapshot,
  UiSourceDef,
} from "./types.ts";

type ArgBag = string | number | boolean | null;

/**
 * Resolve catalog data sources against runtime snapshot / app context.
 * Missing arg keys are omitted (never invented).
 */
export function resolveDataSources(
  def: UiSourceDef,
  snapshot?: UiSnapshot | null,
  appContext?: Partial<UiAppContext> | null,
): ResolvedDataSource[] {
  const sources = def.dataSources ?? [];
  const snap = snapshot ?? {};
  const app = appContext ?? {};

  return sources.map((source) => {
    const args = resolveArgs(source.argKeys ?? [], snap, app);
    const match = resolveArgs(source.listMatchKeys ?? [], snap, app);
    const probe = enrichProbe(fillProbe(source.probeTemplate, args), match);
    return { source, args, match, probe };
  });
}

function resolveArgs(
  keys: string[],
  snap: UiSnapshot,
  app: Partial<UiAppContext>,
): Record<string, ArgBag> {
  const out: Record<string, ArgBag> = {};
  for (const key of keys) {
    const v = lookupArg(key, snap, app);
    if (v !== undefined) out[key] = v;
  }
  return out;
}

function lookupArg(
  key: string,
  snap: UiSnapshot,
  app: Partial<UiAppContext>,
): ArgBag | undefined {
  // Snapshot wins; allow common aliases.
  const candidates = [key, ...aliases(key)];
  for (const c of candidates) {
    if (Object.prototype.hasOwnProperty.call(snap, c)) {
      return coerceArg(snap[c]);
    }
  }
  // App context (string fields only for known keys)
  if (key === "scoringModel" && app.scoringModel != null) return app.scoringModel;
  if (key === "profile" && app.profile != null) return app.profile;
  if (key === "universeProfile" && app.universeProfile != null) return app.universeProfile;
  if (key === "view" && app.view != null) return app.view;
  return undefined;
}

function aliases(key: string): string[] {
  switch (key) {
    case "symbol":
      return ["ticker", "sym"];
    case "product":
      return ["symbol", "ticker"];
    case "horizonDays":
      return ["horizon", "days"];
    case "daysAgo":
      return ["days"];
    case "feePct":
      return ["fee", "fee_pct"];
    case "rr":
      return ["riskReward", "risk_reward"];
    case "timeframe":
      return ["tf", "range"];
    case "sortKey":
      return ["sort"];
    default:
      return [];
  }
}

function coerceArg(v: unknown): ArgValue | undefined {
  if (v === undefined) return undefined;
  if (v === null) return null;
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") return v;
  // arrays/objects not expanded into scalar args
  return undefined;
}

function fillProbe(
  template: string | undefined,
  args: Record<string, ArgValue>,
): string | null {
  if (!template) return null;
  let out = template;
  for (const [k, v] of Object.entries(args)) {
    const token = `{${k}}`;
    if (!out.includes(token)) continue;
    const rendered =
      typeof v === "string" ? JSON.stringify(v).slice(1, -1) /* unquoted inside quotes */ : String(v);
    // Prefer replacing "{key}" quoted forms first
    out = out.split(`"{${k}}"`).join(typeof v === "string" ? JSON.stringify(v) : String(v));
    out = out.split(token).join(rendered);
  }
  // If required placeholders remain, still return (agent sees incomplete recipe)
  return out;
}

/** Append client-side list filter so agents re-fetch the exact row. */
function enrichProbe(
  probe: string | null,
  match: Record<string, ArgValue>,
): string | null {
  if (!probe || Object.keys(match).length === 0) return probe;
  const parts = Object.entries(match).map(([k, v]) => {
    if (typeof v === "string") return `${k}===${JSON.stringify(v)}`;
    return `${k}===${String(v)}`;
  });
  return `${probe} /* find ${parts.join(" && ")} */`;
}
