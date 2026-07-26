import type { UiSnapshot } from "./types.ts";

const BLOCKED_KEY =
  /token|secret|password|smtp|apikey|api_key|refresh|access_token|authorization|cookie|session|private_key|display_name|email/i;

const MAX_DEPTH = 4;
const MAX_ARRAY = 12;
const MAX_STRING = 200;
const MAX_KEYS = 48;
const MAX_TOTAL_CHARS = 6000;

export function sanitizeSnapshot(raw: UiSnapshot | null | undefined): UiSnapshot {
  if (!raw || typeof raw !== "object") return {};
  const out = walk(raw, 0) as UiSnapshot;
  const json = JSON.stringify(out);
  if (json.length <= MAX_TOTAL_CHARS) return out;
  return {
    ...out,
    _truncated: true,
    _note: `snapshot truncated from ${json.length} chars`,
  };
}

function walk(value: unknown, depth: number): unknown {
  if (value == null) return value;
  if (typeof value === "string") {
    return value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}…` : value;
  }
  if (typeof value === "number" || typeof value === "boolean") return value;
  if (typeof value === "bigint") return value.toString();
  if (typeof value === "function" || typeof value === "symbol") return undefined;
  if (depth >= MAX_DEPTH) return "[max-depth]";

  if (Array.isArray(value)) {
    // Never dump candle/spark series
    if (value.length > 0 && typeof value[0] === "number" && value.length > MAX_ARRAY) {
      return { _type: "number[]", length: value.length };
    }
    return value.slice(0, MAX_ARRAY).map((v) => walk(v, depth + 1));
  }

  if (typeof value === "object") {
    const obj = value as Record<string, unknown>;
    const keys = Object.keys(obj).slice(0, MAX_KEYS);
    const out: Record<string, unknown> = {};
    for (const k of keys) {
      if (BLOCKED_KEY.test(k)) continue;
      if (k === "spark" || k === "candles" || k === "history") {
        const v = obj[k];
        out[`${k}Len`] = Array.isArray(v) ? v.length : v == null ? 0 : 1;
        continue;
      }
      const walked = walk(obj[k], depth + 1);
      if (walked !== undefined) out[k] = walked;
    }
    return out;
  }
  return String(value);
}

export function isBlockedKey(key: string): boolean {
  return BLOCKED_KEY.test(key);
}
