import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { pickPageTarget } from "../e2e/native/cdp-client.mjs";

describe("pickPageTarget", () => {
  it("prefers Vantage localhost page over blank targets", () => {
    const picked = pickPageTarget([
      { type: "page", title: "", url: "about:blank", webSocketDebuggerUrl: "ws://a" },
      {
        type: "page",
        title: "Vantage",
        url: "http://localhost:5173/",
        webSocketDebuggerUrl: "ws://vantage",
      },
      { type: "service_worker", title: "sw", url: "chrome-extension://x", webSocketDebuggerUrl: "ws://sw" },
    ]);
    assert.equal(picked.webSocketDebuggerUrl, "ws://vantage");
  });

  it("returns null when no websocket targets", () => {
    assert.equal(pickPageTarget([{ type: "page", title: "x", url: "http://x" }]), null);
  });
});

describe("financial class substring trap", () => {
  function normClass(c) {
    return String(c ?? "")
      .toLowerCase()
      .replace(/[^a-z0-9]/g, "");
  }
  function isFinancialClass(c) {
    const n = normClass(c);
    return (
      n === "financialservices" ||
      n === "financial" ||
      n.startsWith("financialservices") ||
      (n.includes("financial") && !n.includes("nonfinancial"))
    );
  }

  it("does not treat operating_non_financial as financial", () => {
    assert.equal(isFinancialClass("operating_non_financial"), false);
    assert.equal(isFinancialClass("OperatingNonFinancial"), false);
    assert.equal(isFinancialClass("financial_services"), true);
    assert.equal(isFinancialClass("FinancialServices"), true);
  });
});
