/**
 * REQUIRED launcher for Windows live / agent / manual QA.
 *
 * Standing rule (AGENTS.md): QA se hace con profile `qa` — always — unless the
 * user explicitly orders another universe. This script is the supported path.
 *
 * Sets DS_UNIVERSE_PROFILE=qa (locks ≤20 symbols). Do not use bare `tauri dev`
 * for QA. Do not use `tauri dev -- -- --profile qa` (Cargo steals --profile).
 *
 * Usage (from apps/windows):
 *   npm run tauri:dev:qa
 *   node scripts/run-tauri-dev-qa.mjs
 *   node scripts/run-tauri-dev-qa.mjs --no-watch
 *
 * Extra argv after the script name are forwarded to `tauri dev` (not the app).
 */
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const windowsRoot = path.resolve(__dirname, "..");

process.env.DS_UNIVERSE_PROFILE = "qa";

const tauriJs = path.join(windowsRoot, "node_modules", "@tauri-apps", "cli", "tauri.js");
const extra = process.argv.slice(2);

const child = spawn(process.execPath, [tauriJs, "dev", ...extra], {
  cwd: windowsRoot,
  stdio: "inherit",
  env: process.env,
  windowsHide: false,
});

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 1);
});

child.on("error", (err) => {
  console.error("failed to start tauri dev (qa):", err);
  process.exit(1);
});
