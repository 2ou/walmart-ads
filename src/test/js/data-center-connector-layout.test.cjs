const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/index.html"), "utf8");
function extract(start, end) {
  const from = html.indexOf(start);
  const to = html.indexOf(end, from + start.length);
  assert.ok(from >= 0 && to > from, `Missing function: ${start}`);
  return html.slice(from, to);
}

function fixture() {
  const elements = Object.fromEntries([
    "#dataCenterDomesticMcp", "#connectorBrowserNote", "#connectorWorkspaceList",
    "#connectorRunHistoryList", "#domesticMcpRunHistoryList"
  ].map((id) => [id, { innerHTML: "", dataset: {} }]));
  const requests = [];
  const bridge = [];
  const sources = [
    { sourceCode: "LINGXING_INVENTORY", enabled: true, status: "SUCCESS", recordCount: 954, lastSuccessAt: "2026-11-06T12:00:00", message: "可用库存 152" },
    { sourceCode: "LINGXING_WFS", enabled: true, status: "SUCCESS", recordCount: 20 },
    { sourceCode: "SUPPLY_CHAIN", enabled: true, status: "SUCCESS", recordCount: 30 }
  ];
  const state = { domesticMcpSubmitting: new Set(), connectors: { workspaces: [{ workspaceId: 1, storeName: "PINKSIR", storeAbbreviation: "PNK",
    systems: [], browserBridge: { online: true }, sources,
    recentRuns: [{ sourceCode: "LINGXING_INVENTORY", status: "SUCCESS", message: "库存" },
      { sourceCode: "LINGXING_WFS", status: "SUCCESS", message: "货件" }] }] } };
  const ctx = vm.createContext({
    state, Date, Number,
    $: (id) => elements[id], escapeHtml: (text) => String(text ?? ""), integer: (n) => String(n),
    connectorDateTime: () => "时间", connectorElapsed: () => "1 秒", connectorProgressStep: () => 1,
    connectorSourceCopy: { LINGXING_WFS: "领星 WFS 货件", SUPPLY_CHAIN: "供应链" },
    connectorStatusCopy: { SUCCESS: "同步成功", RUNNING: "同步中" },
    renderDataCenterOverview() {}, updateConnectorAlert() {}, renderSupplyChainBridge() {},
    async connectorAction(url, body) { requests.push(body.source); },
    async publishCurrentChromeBridge(id, command) { bridge.push(command.source); },
    showToast(message) { throw new Error(message); }
  });
  vm.runInContext(extract("function renderConnectorRunHistory()", "const connectorSourceCopy ="), ctx);
  vm.runInContext(extract("function renderDomesticMcpCards(", "function scheduleConnectorRefresh()"), ctx);
  vm.runInContext(extract("async function startConnectorSync(", "async function loadBatches()"), ctx);
  return { ctx, elements, requests, bridge };
}

test("domestic inventory status and history are separated from browser connector", () => {
  const mcp = extract('aria-labelledby="dataCenterSyncPlanTitle"', '<h2>采集连接器</h2>');
  assert.match(mcp, /领星 MCP 同步计划/);
  assert.match(mcp, /data-center-sync-plan-grid[\s\S]*id="dataCenterSyncPlanCards"[\s\S]*id="dataCenterDomesticMcp"/);
  const { ctx, elements } = fixture();
  vm.runInContext("renderConnectorStatus()", ctx);
  assert.match(elements["#dataCenterDomesticMcp"].innerHTML, /国内仓库存/);
  assert.match(elements["#dataCenterDomesticMcp"].innerHTML, /data-domestic-mcp-sync="1"/);
  assert.match(elements["#dataCenterDomesticMcp"].innerHTML, /上次写入<b>954<\/b>/);
  assert.doesNotMatch(elements["#dataCenterDomesticMcp"].innerHTML, /data-connector-sync/);
  assert.doesNotMatch(elements["#connectorWorkspaceList"].innerHTML, /国内仓库存|LINGXING_INVENTORY/);
  assert.match(elements["#connectorWorkspaceList"].innerHTML, /LINGXING_WFS/);
  assert.match(elements["#connectorWorkspaceList"].innerHTML, /SUPPLY_CHAIN/);
  assert.match(elements["#domesticMcpRunHistoryList"].innerHTML, /库存/);
  assert.doesNotMatch(elements["#connectorRunHistoryList"].innerHTML, /国内仓库存/);
});

test("browser sync all does not start the MCP inventory task", async () => {
  const { ctx, requests, bridge } = fixture();
  await vm.runInContext("startConnectorSync(1, 'BROWSER_ALL')", ctx);
  assert.deepEqual(requests, ["LINGXING_WFS", "SUPPLY_CHAIN"]);
  assert.deepEqual(bridge, ["LINGXING_WFS", "SUPPLY_CHAIN"]);
});

test("manual domestic MCP sync does not request the browser bridge", async () => {
  const { ctx, requests, bridge } = fixture();
  await vm.runInContext("startConnectorSync(1, 'LINGXING_INVENTORY')", ctx);
  assert.deepEqual(requests, []);
  await vm.runInContext("startDomesticMcpSync(1, {})", ctx);
  assert.deepEqual(requests, ["LINGXING_INVENTORY"]);
  assert.deepEqual(bridge, []);
});

test("running MCP inventory does not show the browser connector as syncing", () => {
  const { ctx, elements } = fixture();
  vm.runInContext("state.connectors.workspaces[0].sources[0].status='RUNNING'; state.connectors.workspaces[0].syncing=true; renderConnectorStatus()", ctx);
  assert.match(elements["#dataCenterDomesticMcp"].innerHTML, /disabled>同步中…/);
  assert.match(elements["#connectorBrowserNote"].innerHTML, /当前 Chrome 已连接/);
  assert.doesNotMatch(elements["#connectorWorkspaceList"].innerHTML, /正在同步/);
});
