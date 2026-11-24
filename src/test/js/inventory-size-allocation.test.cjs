const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/index.html"), "utf8");
function extract(start, end) {
  const from = html.indexOf(start), to = html.indexOf(end, from + start.length);
  assert.ok(from >= 0 && to > from);
  return html.slice(from, to);
}
function context() {
  const ctx = vm.createContext({
    number: (v) => Number(v || 0), integer: (v) => String(v), escapeHtml: (v) => String(v ?? ""),
    state: { inventoryTargetDays: 60, inventoryByStyleDetailSort: { key: "color", dir: "asc" } },
    inventoryRiskCopy: { OUT_OF_STOCK: { badge: "risk", label: "已断货" }, HEALTHY: { badge: "ok", label: "正常" } },
    daysText: (v) => `${v} 天`, thumbTag: () => ""
  });
  vm.runInContext(extract("function inventoryHasProjectedDemand(", "function renderInventoryByStyle("), ctx);
  vm.runInContext(extract("const INVENTORY_SIZE_RANK =", "function renderInventoryStyleDashboard("), ctx);
  return ctx;
}

test("separate WFS and total columns include all four source quantities per size", () => {
  const ctx = context();
  vm.runInContext(`globalThis.snapshot = {domesticBySku:new Map([['PNK-A',3],['PNK-B',7]]), skus:[
    {sku:'PNK-A',color:'Black',size:'1X',availableUnits:2,domesticUnits:3,productionUnits:4,transitUnits:5,risk:'HEALTHY',forecastDailyUnits:1,projectedDaysSupply:5},
    {sku:'PNK-B',color:'Black',size:'2X',availableUnits:0,domesticUnits:7,productionUnits:0,transitUnits:0,risk:'OUT_OF_STOCK',forecastDailyUnits:1,projectedDaysSupply:0}
  ]}`, ctx);
  const result = vm.runInContext("inventoryStyleSkcRows(snapshot)", ctx)[0];
  assert.equal(result.totalStock, 21);
  assert.equal(result.sizesSorted[0].totalStock, 14);
  assert.equal(result.sizesSorted[1].totalStock, 7);
  const rendered = vm.runInContext("inventoryStyleSkcTable(snapshot)", ctx);
  assert.match(rendered, /WFS 可售/);
  assert.match(rendered, /尺码分配 <span class="skc-size-head-note">总库存/);
  assert.match(rendered, /总库存 14：WFS 2、国内仓 3、做货 4、路途 5/);
  assert.match(rendered, /国内仓有货待发/);
});

test("coverage uses the first discontinuity and labels a horizon lower bound", () => {
  const ctx = context();
  assert.equal(vm.runInContext("inventoryContinuousCoverage([{forecastDailyUnits:1,projectedDaysSupply:5},{forecastDailyUnits:1,projectedDaysSupply:60,coverageLowerBound:true}]).days", ctx), 5);
  assert.equal(vm.runInContext("inventoryCoverageText(60,true)", ctx), "≥ 60.0 天");
  assert.equal(vm.runInContext("inventoryContinuousCoverage([{forecastDailyUnits:0,hasProjectedDemand:true,projectedDaysSupply:3}]).days", ctx), 3);
});

test("sales and product dashboard share planned coverage and never show a stale target", () => {
  const ctx = context();
  vm.runInContext(extract("function coverageKey(", "function salesFilteredRows("), ctx);
  vm.runInContext(extract("function productCoverageSignal(", "function renderProductDetail("), ctx);
  vm.runInContext(`state.inventoryCoverage = {targetDays:60, planningReady:true,
    styleMap:new Map([['PNK001',{projectedDays:60,coverageLowerBound:true,risk:'HEALTHY'}]]),
    skuMap:new Map([['PNK001-A',{projectedDays:0,coverageLowerBound:false,risk:'OUT_OF_STOCK'}]])}`, ctx);
  assert.equal(vm.runInContext("sharedCoverageText(sharedCoverage('pnk001'))", ctx), "≥ 60.0 天");
  assert.equal(vm.runInContext("sharedCoverageText(sharedCoverage('pnk001-a',false))", ctx), "0.0 天");
  assert.match(vm.runInContext("productCoverageSignal('pnk001')", ctx), /连续可售 ≥ 60.0 天/);
  vm.runInContext("state.inventoryTargetDays = 90", ctx);
  assert.equal(vm.runInContext("sharedCoverageText(sharedCoverage('pnk001'))", ctx), "计算中");
});
