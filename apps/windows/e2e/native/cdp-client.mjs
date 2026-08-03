/**
 * Minimal WebView2 / Chromium DevTools Protocol client (loopback only).
 * Shared by native e2e and the attach-mode agent CLI (`scripts/ds-ui.mjs`).
 */
export function delay(ms) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
}

export async function waitUntil(description, predicate, timeoutMs = 30_000, intervalMs = 200) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await delay(intervalMs);
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? `: ${lastError}` : ""}`);
}

export class CdpClient {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
  }

  async connect() {
    await new Promise((resolveOpen, rejectOpen) => {
      this.socket.addEventListener("open", resolveOpen, { once: true });
      this.socket.addEventListener("error", rejectOpen, { once: true });
    });
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);
      if (message.id == null) return;
      const waiter = this.pending.get(message.id);
      if (!waiter) return;
      this.pending.delete(message.id);
      if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
      else waiter.resolve(message.result);
    });
  }

  call(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolveCall, rejectCall) => {
      this.pending.set(id, { resolve: resolveCall, reject: rejectCall });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate(expression) {
    const response = await this.call("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (response.exceptionDetails) {
      throw new Error(response.exceptionDetails.exception?.description ?? "CDP evaluation failed");
    }
    return response.result.value;
  }

  close() {
    try {
      this.socket.close();
    } catch {
      // ignore
    }
  }
}

/** Prefer the live Vantage page over blank/devtools/service-worker targets. */
export function pickPageTarget(targets) {
  const pages = (targets ?? []).filter((t) => t.webSocketDebuggerUrl);
  if (pages.length === 0) return null;

  const score = (t) => {
    var s = 0;
    var title = String(t.title ?? "").toLowerCase();
    var url = String(t.url ?? "").toLowerCase();
    var type = String(t.type ?? "");
    if (type === "page") s += 10;
    if (title.includes("vantage")) s += 50;
    if (url.includes("localhost:5173") || url.includes("127.0.0.1:5173")) s += 40;
    if (url.includes("tauri.localhost") || url.includes("https://tauri")) s += 30;
    if (url.startsWith("http://") || url.startsWith("https://")) s += 5;
    if (title.includes("devtools") || url.includes("devtools")) s -= 100;
    if (type === "service_worker" || type === "worker") s -= 50;
    return s;
  };

  return [...pages].sort((a, b) => score(b) - score(a))[0] ?? null;
}

/**
 * @param {{ host?: string, port?: number, timeoutMs?: number }} opts
 * @returns {Promise<{ client: CdpClient, target: object, baseUrl: string }>}
 */
export async function attachToWebView(opts = {}) {
  const host = opts.host ?? process.env.DS_UI_CDP_HOST ?? "127.0.0.1";
  const port = Number(opts.port ?? process.env.DS_UI_CDP_PORT ?? 9222);
  const timeoutMs = opts.timeoutMs ?? Number(process.env.DS_UI_CDP_TIMEOUT_MS ?? 10_000);
  const baseUrl = `http://${host}:${port}`;

  const target = await waitUntil(
    `WebView2 CDP target on ${baseUrl}`,
    async () => {
      let response;
      try {
        response = await fetch(`${baseUrl}/json/list`);
      } catch {
        return null;
      }
      if (!response.ok) return null;
      const targets = await response.json();
      return pickPageTarget(targets);
    },
    timeoutMs,
  );

  const client = new CdpClient(target.webSocketDebuggerUrl);
  await client.connect();
  await client.call("Runtime.enable");
  return { client, target, baseUrl };
}

export async function tauriInvoke(client, command, args = {}) {
  return client.evaluate(
    `window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)}, ${JSON.stringify(args)})`,
  );
}

export async function listCdpTargets(opts = {}) {
  const host = opts.host ?? process.env.DS_UI_CDP_HOST ?? "127.0.0.1";
  const port = Number(opts.port ?? process.env.DS_UI_CDP_PORT ?? 9222);
  const baseUrl = `http://${host}:${port}`;
  const response = await fetch(`${baseUrl}/json/list`);
  if (!response.ok) {
    throw new Error(`CDP list failed: HTTP ${response.status} from ${baseUrl}`);
  }
  return { baseUrl, targets: await response.json() };
}

/** Probe capabilities without throwing when agent/feed missing. */
export async function probeAgentSurface(client) {
  return client.evaluate(`(() => {
    const hasInvoke = typeof window.__TAURI_INTERNALS__?.invoke === "function";
    const agent = window.__DS_AGENT__;
    return {
      hasInvoke,
      hasAgent: !!(agent && typeof agent.openSymbol === "function"),
      agentVersion: agent?.version ?? null,
      selectedSymbol: agent?.snapshot?.()?.selectedSymbol ?? null,
      title: document.title,
    };
  })()`);
}
