/** Shared types for UI inspect → agent-ready clipboard refs. */

export type UiSnapshot = Record<string, unknown>;

/** How the UI piece gets its data (Tauri command, pure client, or upstream). */
export type DataSourceKind = "tauri" | "client" | "upstream";

export type DataSourceRole = "primary" | "enrich" | "context";

/**
 * Declares a backend/client data path for a UI surface.
 * Agents use this to open handlers instead of guessing.
 */
export interface UiDataSource {
  /** Stable logical id, e.g. "opportunities" */
  id: string;
  kind: DataSourceKind;
  /** Tauri invoke command when kind === "tauri" */
  command?: string;
  /** Client wrapper, e.g. apps/windows/src/api.ts#getOpportunities */
  client?: string;
  /** Rust / handler path, e.g. apps/windows/src-tauri/src/commands.rs#get_opportunities */
  impl?: string;
  /** Domain modules behind the handler */
  domain?: string[];
  /**
   * Keys pulled from snapshot / appContext to fill invoke args.
   * e.g. ["symbol"] → args: { symbol: snapshot.symbol }
   */
  argKeys?: string[];
  /**
   * For list endpoints (no invoke args): match these snapshot keys client-side
   * after re-fetch, e.g. ["symbol"] → match: { symbol: "MA" }.
   */
  listMatchKeys?: string[];
  /**
   * Optional probe recipe template. Use `{symbol}` placeholders matching argKeys.
   * Example: `api.getSymbolDetail("{symbol}")`
   */
  probeTemplate?: string;
  role?: DataSourceRole;
  note?: string;
}

export interface UiSourceDef {
  /** Stable id, e.g. dashboard.v2.planCard */
  id: string;
  /** Short human label */
  label: string;
  /** Repo-relative component path */
  component: string;
  /** Function / subcomponent region name */
  region: string;
  /** Logic modules with #symbol for agent navigation */
  related?: string[];
  /** Ordered hints for the agent */
  agentHints?: string[];
  /** Optional static line range hint (not runtime-accurate) */
  approxLines?: string;
  /** Backend / client data sources that feed this surface */
  dataSources?: UiDataSource[];
}

export interface UiAppContext {
  view: string;
  scoringModel: string;
  regimeScoring: boolean;
  dashboardEdition: string;
  profile: string;
  lang?: string;
  theme?: string;
  assetFilter?: string;
  universeProfile?: string;
}

export interface UiRefPayloadInput {
  def: UiSourceDef;
  snapshot?: UiSnapshot | null;
  appContext?: Partial<UiAppContext> | null;
  visibleText?: string | null;
}

/** Resolved data source ready for payload rendering. */
export interface ResolvedDataSource {
  source: UiDataSource;
  args: Record<string, string | number | boolean | null>;
  /** Client-side filter when invoke returns a list */
  match: Record<string, string | number | boolean | null>;
  probe: string | null;
}
