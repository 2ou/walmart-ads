const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/index.html"), "utf8");
test("complete inline application module parses", () => {
  const from = html.indexOf('<script type="module">');
  const to = html.indexOf('</script>', from);
  assert.ok(from >= 0 && to > from);
  const source = html.slice(from + '<script type="module">'.length, to);
  const check = spawnSync(process.execPath, ["--input-type=module", "--check"], {
    input: source,
    encoding: "utf8",
  });
  assert.ifError(check.error);
  assert.equal(check.status, 0, check.stderr);
});
function section(from, until) {
  const start = html.indexOf(from), end = html.indexOf(until, start + from.length);
  assert.ok(start >= 0 && end > start);
  return html.slice(start, end);
}
function fixture() {
  const rows = [];
  for (let day = 1; day <= 8; day++) rows.push({ date: `2026-11-0${day}`, hasSales: true, hasAds: day <= 7, orders: 10, revenue: 100, spend: day <= 7 ? 10 : 0, adSales: day <= 7 ? 30 : 0, adOrders: day <= 7 ? 2 : 0 });
  const ctx = vm.createContext({
    DATA: { meta: { hasSalesData: true, hasAdData: true, orderLatestDate: "2026-11-08", adSettledDate: "2026-11-07" }, daily: rows },
    dashboardVersion: { visibleDate: "2026-11-09" },
    state: { periodDays: 7, customPeriodActive: false },
    isoDate: (value) => new Date(`${value}T00:00:00`),
    addDays: (source, days) => { const result = new Date(source); result.setDate(result.getDate() + days); return result; },
    toIso: (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
    inclusiveDays: (a, b) => Math.round((new Date(`${b}T00:00:00`) - new Date(`${a}T00:00:00`)) / 86400000) + 1,
    sum: (items, key) => items.reduce((acc, row) => acc + Number(row[key] || 0), 0),
  });
  vm.runInContext(section("function dashboardVisibleDate(", "function analysisQuery("), ctx);
  return ctx;
}
test("sales and ad windows advance independently and calculate equal-length prior periods", () => {
  const ctx = fixture();
  assert.equal(vm.runInContext("dashboardRange('sales').startDate", ctx), "2026-11-02");
  assert.equal(vm.runInContext("dashboardRange('sales').endDate", ctx), "2026-11-08");
  assert.equal(vm.runInContext("dashboardRange('ads').endDate", ctx), "2026-11-07");
  assert.equal(vm.runInContext("dashboardRange('common').endDate", ctx), "2026-11-07");
  assert.equal(vm.runInContext("dashboardRange('sales',true).startDate", ctx), "2026-10-26");
  assert.equal(vm.runInContext("dashboardAggregate(DATA.daily,dashboardRange('sales')).revenue", ctx), 700);
  assert.equal(vm.runInContext("dashboardAggregate(DATA.daily,dashboardRange('ads')).roas", ctx), 3);
  assert.equal(vm.runInContext("dashboardComplete(dashboardRange('ads'),'ads')", ctx), true);
});

test("cards use each source's window rather than the shared window", () => {
  const ctx = fixture();
  const elements = new Map(["#metricGrid", "#statusHeadline", "#statusDetail", "#statusRange"].map((key) => [key, { innerHTML: "", textContent: "" }]));
  Object.assign(ctx, {
    $: (key) => elements.get(key),
    metricCard: (label, value, change, note) => `[${label}:${value}:${note}]`,
    money: (n) => `$${n}`, integer: (n) => String(n), ratio: (n) => `${n}x`,
    icons: {}, delta: () => ({ text: "comparison", cls: "flat", arrow: "" }),
    renderTrend: () => {}, renderOrderMix: () => {},
  });
  vm.runInContext("state.thresholds={targetRoas:5,floorRoas:1.5}", ctx);
  vm.runInContext(section("function renderMetrics()", "function renderTrend()"), ctx);
  vm.runInContext("renderMetrics()", ctx);
  const rendered = elements.get("#metricGrid").innerHTML;
  assert.match(rendered, /总销售额:\$700:2026-11-02 至 2026-11-08/);
  assert.match(rendered, /广告费:\$70:2026-11-01 至 2026-11-07/);
  assert.match(rendered, /广告 ROAS:3x:2026-11-01 至 2026-11-07/);
  assert.doesNotMatch(elements.get("#statusDetail").textContent, /自然订单 \d/);
});
test("missing data is not complete or mistaken for a zero day", () => {
  const ctx = fixture();
  vm.runInContext("DATA.daily[3].hasAds=false", ctx);
  assert.equal(vm.runInContext("dashboardComplete(dashboardRange('ads'),'ads')", ctx), false);
  vm.runInContext("DATA.meta.hasAdData=false", ctx);
  assert.equal(vm.runInContext("dashboardRange('ads')", ctx), null);
  assert.equal(vm.runInContext("dashboardRange('common')", ctx), null);
  assert.equal(vm.runInContext("dashboardHasAny({startDate:'2026-11-09',endDate:'2026-11-09'},'sales')", ctx), false);
});

test("local version polling reloads dashboard only when changed, without calling the MCP", async () => {
  const calls = [];
  let revision = 1;
  const ctx = vm.createContext({
    DATA: { meta: { workspaceId: 1 } }, state: { view: "sales" }, dashboardDataLoaded: true, dashboardVersion: null,
    dashboardVersionKey: "", dashboardRefreshPromise: null,
    $: () => null,
    fetch: async (url) => { calls.push(url); return { ok: true, json: async () => ({ workspaceId: 1, revision }) }; },
    ensureDashboardData: async () => calls.push("local-data"),
    renderDashboardFreshness: () => {}, showToast: () => {},
  });
  vm.runInContext(section("async function performDashboardRefresh(", "function renderAll("), ctx);
  await vm.runInContext("refreshDashboard()", ctx);
  await vm.runInContext("refreshDashboard()", ctx);
  revision = 2;
  await vm.runInContext("refreshDashboard()", ctx);
  assert.deepEqual(calls, ["/api/dashboard/version", "local-data", "/api/dashboard/version", "/api/dashboard/version", "local-data"]);
});

test("changing stores discards visible old-store data before rendering new data", async () => {
  const elements = new Map();
  for (const id of ["#metricGrid", "#topSkuBody", "#trendChart", "#orderMix"]) elements.set(id, { innerHTML: "old store" });
  let loads = 0;
  const ctx = vm.createContext({
    DATA: { meta: { workspaceId: 1 } }, state: { view: "sales" }, dashboardDataLoaded: true,
    dashboardVersion: { workspaceId: 1 }, dashboardVersionKey: "old", dashboardRefreshPromise: null,
    $: (id) => elements.get(id) || null,
    emptyRow: () => "loading current store",
    fetch: async () => ({ ok: true, json: async () => ({ workspaceId: 2 }) }),
    ensureDashboardData: async () => { loads++; assert.equal(elements.get("#metricGrid").innerHTML, ""); ctx.DATA.meta.workspaceId = 2; ctx.dashboardDataLoaded = true; },
    renderDashboardFreshness: () => {}, showToast: () => {},
  });
  vm.runInContext(section("async function performDashboardRefresh(", "function renderAll("), ctx);
  await vm.runInContext("refreshDashboard()", ctx);
  assert.equal(loads, 1);
  assert.equal(ctx.DATA.meta.workspaceId, 2);
  assert.equal(elements.get("#topSkuBody").innerHTML, "loading current store");
});
test("custom range preserves start, exposes partial tail and obeys visible-date cutoff", () => {
  const ctx = fixture();
  vm.runInContext("state.customPeriodActive=true;state.customPeriodStartDate='2026-11-05';state.customPeriodEndDate='2026-11-09'", ctx);
  assert.equal(vm.runInContext("dashboardRange('ads').startDate", ctx), "2026-11-05");
  assert.equal(vm.runInContext("dashboardRange('ads').endDate", ctx), "2026-11-07");
  assert.equal(vm.runInContext("dashboardRange('ads').partial", ctx), true);
  vm.runInContext("dashboardVersion.visibleDate='2026-11-07'", ctx);
  assert.equal(vm.runInContext("dashboardRange('sales').endDate", ctx), "2026-11-07");
  vm.runInContext("state.customPeriodStartDate='2026-11-08'", ctx);
  assert.equal(vm.runInContext("dashboardRange('sales')", ctx), null);
});

test("trend leaves the latest missing ad day blank and reports it in the tooltip", () => {
  const ctx = fixture();
  const listeners = {};
  const element = () => ({ innerHTML: "", style: {}, hidden: false, clientWidth: 900,
    classList: { add() {}, remove() {} }, setAttribute() {}, addEventListener(name, fn) { listeners[name] = fn; },
    getBoundingClientRect() { return { left: 0, width: 900 }; } });
  const svg = element(), chart = element(), hit = element(), crosshair = element(), markers = element(), tooltip = element();
  chart.querySelector = () => svg;
  Object.assign(ctx, { $: (selector) => ({ "#trendChart": chart, "#dashboardTrendHitTarget": hit,
    "#dashboardTrendCrosshair": crosshair, "#dashboardTrendMarkers": markers, "#dashboardTrendTooltip": tooltip })[selector],
    getComputedStyle: () => ({ paddingLeft: "0", paddingRight: "0" }),
    number: (n) => Number(n || 0), money: (n) => `$${Number(n || 0)}`, integer: (n) => String(n || 0),
    dateText: (s) => s, fullDateText: (s) => s, document: { activeElement: null },
  });
  vm.runInContext(section("function renderTrend()", "function renderOrderMix("), ctx);
  vm.runInContext("renderTrend()", ctx);
  assert.doesNotMatch(chart.innerHTML, /cy="null"/);
  assert.equal((chart.innerHTML.match(/class="chart-point"/g) || []).length, 20);
  listeners.focus();
  assert.match(tooltip.innerHTML, /广告投入<\/span><b>暂无记录/);
  assert.match(tooltip.innerHTML, /销量<\/span><b>10 件/);
});
