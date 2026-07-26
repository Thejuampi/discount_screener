import { resolveDataSources } from "./resolveDataSources.ts";
import { sanitizeSnapshot } from "./sanitize.ts";
import type {
  ResolvedDataSource,
  UiAppContext,
  UiRefPayloadInput,
  UiSnapshot,
} from "./types.ts";

const SCHEMA = "ds-ui-ref v1";

export function buildUiRefPayload(input: UiRefPayloadInput): string {
  const { def } = input;
  const snap = sanitizeSnapshot(input.snapshot ?? {});
  const app = input.appContext ?? {};
  const visible = (input.visibleText ?? "").trim().replace(/\s+/g, " ").slice(0, 240);
  const dataSources = resolveDataSources(def, input.snapshot ?? {}, app);

  const lines: string[] = [];
  lines.push("```" + SCHEMA);
  lines.push("## What");
  lines.push(`id: ${def.id}`);
  lines.push(`label: ${def.label}`);
  if (visible) lines.push(`visible: ${JSON.stringify(visible)}`);

  lines.push("");
  lines.push("## Where (construction)");
  lines.push(`component: ${def.component}`);
  lines.push(`region: ${def.region}`);
  if (def.approxLines) lines.push(`approxLines: ${def.approxLines}`);
  if (def.related && def.related.length > 0) {
    lines.push("related:");
    for (const r of def.related) lines.push(`  - ${r}`);
  }

  if (dataSources.length > 0) {
    lines.push("");
    lines.push("## Data sources");
    lines.push(
      "note: Tauri invoke commands (not HTTP). Open client/impl paths; probe is a recipe for api.ts.",
    );
    for (const resolved of dataSources) {
      writeDataSource(lines, resolved);
    }
  }

  lines.push("");
  lines.push("## Runtime (safe snapshot)");
  writeKv(lines, "app", "windows");
  writeAppContext(lines, app);
  writeSnapshotFlat(lines, snap);

  if (def.agentHints && def.agentHints.length > 0) {
    lines.push("");
    lines.push("## Agent hints");
    def.agentHints.forEach((h, i) => lines.push(`${i + 1}. ${h}`));
    if (dataSources.length > 0) {
      const primary = dataSources.find((d) => d.source.role === "primary") ?? dataSources[0];
      const n = def.agentHints.length + 1;
      if (primary.source.impl) {
        lines.push(`${n}. Open primary data impl: ${primary.source.impl}`);
      }
      if (primary.probe) {
        lines.push(`${n + 1}. Re-fetch recipe: ${primary.probe}`);
      }
    }
  } else {
    lines.push("");
    lines.push("## Agent hints");
    lines.push(`1. Start at region ${def.region} in ${def.component}`);
    if (def.related?.[0]) lines.push(`2. Follow related module ${def.related[0]}`);
    if (dataSources[0]?.source.impl) {
      lines.push(`3. Open data impl ${dataSources[0].source.impl}`);
    }
  }

  lines.push("```");
  return lines.join("\n");
}

function writeDataSource(lines: string[], resolved: ResolvedDataSource): void {
  const { source, args, probe } = resolved;
  lines.push(`- id: ${source.id}`);
  lines.push(`  kind: ${source.kind}`);
  if (source.role) lines.push(`  role: ${source.role}`);
  if (source.command) lines.push(`  command: ${source.command}`);
  if (source.client) lines.push(`  client: ${source.client}`);
  if (source.impl) lines.push(`  impl: ${source.impl}`);
  if (source.domain && source.domain.length > 0) {
    lines.push("  domain:");
    for (const d of source.domain) lines.push(`    - ${d}`);
  }
  // Always emit args object for tauri (even empty) so agents see invoke shape
  if (source.kind === "tauri" || Object.keys(args).length > 0) {
    lines.push(`  args: ${JSON.stringify(args)}`);
  }
  if (Object.keys(resolved.match).length > 0) {
    lines.push(`  match: ${JSON.stringify(resolved.match)}`);
  }
  if (probe) lines.push(`  probe: ${probe}`);
  if (source.note) lines.push(`  note: ${source.note}`);
}

function writeAppContext(lines: string[], app: Partial<UiAppContext>): void {
  const keys: (keyof UiAppContext)[] = [
    "view",
    "scoringModel",
    "regimeScoring",
    "dashboardEdition",
    "profile",
    "lang",
    "theme",
    "assetFilter",
    "universeProfile",
  ];
  for (const k of keys) {
    const v = app[k];
    if (v !== undefined && v !== null && v !== "") writeKv(lines, k, v);
  }
}

function writeSnapshotFlat(lines: string[], snap: UiSnapshot): void {
  for (const [k, v] of Object.entries(snap)) {
    if (v === undefined) continue;
    if (v !== null && typeof v === "object") {
      writeKv(lines, k, JSON.stringify(v));
    } else {
      writeKv(lines, k, v as string | number | boolean | null);
    }
  }
}

function writeKv(lines: string[], key: string, value: string | number | boolean | null): void {
  lines.push(`${key}: ${formatScalar(value)}`);
}

function formatScalar(value: string | number | boolean | null): string {
  if (value === null) return "null";
  if (typeof value === "string") {
    // keep simple tokens unquoted
    if (/^[\w.+@/-]+$/.test(value) && value.length < 80) return value;
    return JSON.stringify(value);
  }
  return String(value);
}
