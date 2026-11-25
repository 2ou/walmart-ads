const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

function worker() {
  const local = { walmartWorkbenchCurrentBrowserBridge: { key: "test", baseUrl: "http://localhost:8081" } };
  const session = { "lingxingRequestTemplate:LINGXING_INVENTORY": { body: "legacy" }, "lingxingRequestTemplate:LINGXING_WFS": { body: "legacy" } };
  const calls = { created: 0, createdUrls: [], reloaded: 0, failures: [], filters: [] };
  const event = { addListener() {} };
  const storage = (data) => ({
    async get(key) {
      const keys = Array.isArray(key) ? key : [key];
      return Object.fromEntries(keys.filter((item) => item in data).map((item) => [item, data[item]]));
    },
    async set(values) { Object.assign(data, values); },
    async remove(key) { for (const item of Array.isArray(key) ? key : [key]) delete data[item]; }
  });
  const context = vm.createContext({
    chrome: {
      storage: { local: storage(local), session: storage(session) },
      tabs: {
        async query() { return []; },
        async create(options) { calls.created++; calls.createdUrls.push(options.url); return { id: calls.created }; },
        async reload() { calls.reloaded++; },
        async sendMessage() {}, async update() {}
      },
      runtime: { id: "test", onMessage: event, onInstalled: event, onStartup: event, getURL: () => "chrome-extension://test/" },
      webRequest: {
        onBeforeRequest: { addListener(listener, filter) { calls.filters.push(...filter.urls); } },
        onBeforeSendHeaders: { addListener(listener, filter) { calls.filters.push(...filter.urls); } }
      },
      alarms: { onAlarm: event, async get() { return {}; }, async create() {} },
      action: { async setBadgeBackgroundColor() {}, async setBadgeText() {} }
    },
    fetch: async (url, options) => {
      if (url.endsWith("/failure")) calls.failures.push(JSON.parse(options.body));
      return { ok: true, status: 200, async json() { return { success: true, data: {} }; } };
    },
    URL, URLSearchParams, TextEncoder, TextDecoder, Uint8Array, AbortSignal, setTimeout, clearTimeout, console
  });
  vm.runInContext(fs.readFileSync(`${__dirname}/service-worker.js`, "utf8"), context);
  return { context, calls, session };
}

test("missing supply session pauses without opening tabs", async () => {
  const { context, calls } = worker();
  await assert.rejects(vm.runInContext("syncSupply()", context), /自动同步已暂停/);
  assert.equal(calls.created, 0);
  assert.equal(calls.reloaded, 0);
  assert.equal(calls.failures.at(-1).source, "SUPPLY_CHAIN");
  assert.equal(calls.failures.at(-1).loginRequired, true);
});

test("an explicit login action opens one page", async () => {
  const { context, calls } = worker();
  await vm.runInContext("runCommand({action:'LOGIN', system:'SUPPLY_CHAIN'})", context);
  assert.equal(calls.created, 1);
});

test("old WFS commands never open a browser page", async () => {
  const { context, calls } = worker();
  await assert.rejects(vm.runInContext("runCommand({action:'OPEN_PAGE', source:'LINGXING_WFS'})", context), /MCP/);
  await assert.rejects(vm.runInContext("runCommand({action:'LOGIN', system:'LINGXING'})", context), /MCP/);
  await vm.runInContext("runCommand({action:'SYNC', source:'LINGXING_WFS'})", context);
  assert.equal(calls.created, 0);
});

test("Lingxing inventory and cargo requests are no longer captured or scheduled", async () => {
  const { context, calls, session } = worker();
  await vm.runInContext("bootstrap()", context);
  assert.deepEqual(calls.filters, ["https://czyxgyl.shangyiku.top/prod-api/*"]);
  assert.equal(session["lingxingRequestTemplate:LINGXING_INVENTORY"], undefined);
  assert.equal(session["lingxingRequestTemplate:LINGXING_WFS"], undefined);
  await vm.runInContext("runCommand({action:'SYNC', source:'LINGXING_INVENTORY'})", context);
  assert.equal(calls.created, 0);
  assert.equal(calls.failures.length, 0);
});
