import { spawn, type ChildProcess } from "node:child_process";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const HOST = "127.0.0.1";
const PORT = 1421;
const DEV_SERVER_URL = `http://${HOST}:${PORT}`;
let vite: ChildProcess | undefined;

async function waitForVite(): Promise<void> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(`${DEV_SERVER_URL}/e2e.html`);
      if (response.ok) return;
    } catch {
      // Vite is still starting.
    }
    await delay(250);
  }
  throw new Error(`Vite did not become ready at ${DEV_SERVER_URL}`);
}

export const config: WebdriverIO.Config = {
  runner: "local",
  specs: ["./e2e/specs/**/*.e2e.ts"],
  maxInstances: 1,
  logLevel: "error",
  bail: 0,
  baseUrl: DEV_SERVER_URL,
  waitforTimeout: 10_000,
  connectionRetryTimeout: 120_000,
  connectionRetryCount: 1,
  capabilities: [
    {
      browserName: "tauri",
      "goog:chromeOptions": {
        args: [
          "--headless=new",
          "--disable-gpu",
          "--disable-dev-shm-usage",
          "--no-sandbox",
          "--window-size=1600,1200",
        ],
      },
      "wdio:tauriServiceOptions": {
        mode: "browser",
        devServerUrl: `${DEV_SERVER_URL}/e2e.html`,
      },
    },
  ],
  services: [
    [
      "@wdio/tauri-service",
      {
        mode: "browser",
        devServerUrl: `${DEV_SERVER_URL}/e2e.html`,
      },
    ],
  ],
  framework: "mocha",
  reporters: ["spec"],
  mochaOpts: {
    ui: "bdd",
    timeout: 30_000,
  },
  onPrepare: async () => {
    const viteEntry = resolve("node_modules/vite/bin/vite.js");
    vite = spawn(
      process.execPath,
      [viteEntry, "--host", HOST, "--port", String(PORT), "--strictPort"],
      { cwd: process.cwd(), stdio: "inherit", windowsHide: true },
    );
    await waitForVite();
  },
  onComplete: () => {
    vite?.kill();
    vite = undefined;
  },
};
