const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/index.html"), "utf8");

function extract(start, end) {
  const from = html.indexOf(start);
  const to = html.indexOf(end, from + start.length);
  assert.ok(from >= 0 && to > from, `Missing source range: ${start}`);
  return html.slice(from, to);
}

function fixture(deliveryDetails, summary = {}, extra = {}) {
  const state = {
    supplyChain: { deliveryDetails, summary, ...extra },
    supplyChainTab: "delivery",
    supplyChainStatus: "all",
    supplyChainSearch: "",
  };
  const ctx = vm.createContext({
    state,
    number: (value) => Number(value) || 0,
    integer: (value) => String(Number(value) || 0),
    escapeHtml: (value) => String(value ?? ""),
    supplyChainDateTime: (value) => value || "—",
  });
  vm.runInContext(extract("function scProductionOrders(", "function renderSupplyChainTable("), ctx);
  return {
    state,
    evaluate: (expression) => vm.runInContext(expression, ctx),
    loadDashboard: () => vm.runInContext(extract("const SC_METRIC_META =", "function renderSupplyChainMetric("), ctx),
  };
}

function delivery(deliveryNo, planFlag, shipQty, qcReceiveQty, deliveryStage, deliveryStageQty) {
  return { deliveryNo, planFlag, shipQty, qcReceiveQty, deliveryStage, deliveryStageQty };
}

test("pending plan and historical receipt gaps do not become confirmed transit", () => {
  const { state, evaluate } = fixture([
    delivery("PENDING", "0", 400, 0, "PENDING_PLAN", 400),
    delivery("PENDING", "未做", 454, 0, "PENDING_PLAN", 454),
    delivery("HISTORY", "1", 80, 32, "HISTORICAL_RECEIPT_GAP", 48),
    delivery("PLAN-ONLY", "1", 17, 0, "UNVERIFIED", 17),
  ]);

  const buckets = evaluate("scDeliveryBuckets(state.supplyChain.deliveryDetails)");
  assert.equal(buckets.PENDING_PLAN.quantity, 854);
  assert.equal(buckets.PENDING_PLAN.rows, 2);
  assert.equal(buckets.HISTORICAL_RECEIPT_GAP.quantity, 48);
  assert.equal(buckets.UNVERIFIED.quantity, 17);
  assert.equal(buckets.IN_TRANSIT, undefined);
  assert.equal(evaluate("scDeliverySheets().reduce((total, sheet) => total + sheet.inTransitQty, 0)"), 0);

  function filtered(status) {
    state.supplyChainStatus = status;
    return Array.from(evaluate("supplyChainFilteredRows()"), (sheet) => sheet.deliveryNo);
  }
  assert.deepEqual(filtered("pending-plan"), ["PENDING"]);
  assert.deepEqual(filtered("historical-gap"), ["HISTORY"]);
  assert.deepEqual(filtered("unverified"), ["PLAN-ONLY"]);
  assert.deepEqual(filtered("in-transit"), []);
});

test("mixed sheets preserve row stages and unknown plans never count as unplanned", () => {
  const { state, evaluate } = fixture([
    delivery("MIXED", "0", 20, 0, "PENDING_PLAN", 20),
    delivery("MIXED", "已做", 13, 6, "IN_TRANSIT", 7),
    delivery("MIXED", null, 5, 0, "UNVERIFIED", 5),
    delivery("UNKNOWN", "unrecognized", 9, 0, undefined, undefined),
  ]);

  const mixed = evaluate("scDeliverySheets().find((sheet) => sheet.deliveryNo === 'MIXED')");
  assert.equal(mixed.planFlag, "mixed");
  assert.equal(mixed.plannedQty, 13);
  assert.equal(mixed.unplannedQty, 20);
  assert.equal(mixed.unknownPlanQty, 5);
  assert.equal(mixed.inTransitQty, 7);
  assert.equal(evaluate("scDeliveryStage(scDeliverySheets()[0]).label"), "多种状态");
  assert.match(evaluate("scDeliveryStageCell(scDeliverySheets()[0])"), /已确认在途 7 件/);
  const unknown = evaluate("scDeliverySheets().find((sheet) => sheet.deliveryNo === 'UNKNOWN')");
  assert.equal(unknown.unplannedQty, 0);
  assert.equal(unknown.unknownPlanQty, 9);
  assert.equal(unknown.inTransitQty, 0);
  assert.equal(unknown.stageBuckets.UNVERIFIED.rows, 1);

  for (const status of ["pending-plan", "in-transit", "planned", "unplanned"]) {
    state.supplyChainStatus = status;
    assert.deepEqual(Array.from(evaluate("supplyChainFilteredRows()"), (sheet) => sheet.deliveryNo), ["MIXED"]);
  }
  state.supplyChainStatus = "unverified";
  assert.deepEqual(Array.from(evaluate("supplyChainFilteredRows()"), (sheet) => sheet.deliveryNo), ["MIXED", "UNKNOWN"]);
});

test("dashboard uses backend transit and workbench WIP totals independently of ERP quantities", () => {
  const { evaluate, loadDashboard } = fixture([
    delivery("PENDING", "0", 854, 0, "PENDING_PLAN", 854),
    delivery("HISTORY", "1", 80, 32, "HISTORICAL_RECEIPT_GAP", 48),
    delivery("PLAN-ONLY", "1", 17, 0, "UNVERIFIED", 17),
  ], {
    pendingPlanQty: 854,
    inTransitQty: 0,
    historicalReceiptGapQty: 48,
    unverifiedQty: 17,
    workbenchWipQty: 976,
    workbenchWipUnmatchedQty: 88,
    workbenchWipTotalQty: 1064,
    workbenchWipSource: "SUPPLY_CHAIN",
  }, {
    productionOrders: [{ orderNo: "ERP", unfinishedQty: 211 }],
    workbenchProductionRows: [
      { orderNo: "MATCHED", sku: "VALID-SKU", wipQty: 976, includedInInventory: true },
      { orderNo: "UNMATCHED", sku: "UNKNOWN-SKU", wipQty: 88, includedInInventory: false },
    ],
  });
  loadDashboard();

  const transit = evaluate("scMetricDashboard('transit')");
  assert.match(transit.stage, /确认在途 0 件/);
  assert.deepEqual(Object.fromEntries(Array.from(transit.balances, (row) => [row.field, row.value])), {
    pendingPlanQty: 854,
    inTransitQty: 0,
    historicalReceiptGapQty: 48,
    unverifiedQty: 17,
  });
  assert.equal(transit.rightBars.length, 0);
  const transitRows = transit.tableRows.match(/<tr>.*?<\/tr>/g);
  assert.match(transitRows.find((row) => row.includes('data-sc-summary-sheet="PENDING"')), /待做发货计划.*<td class="num">854<\/td>/);
  assert.match(transitRows.find((row) => row.includes('data-sc-summary-sheet="HISTORY"')), /历史收货差异.*<td class="num">48<\/td>/);

  const workbench = evaluate("scMetricDashboard('workbench-wip')");
  assert.deepEqual(Array.from(workbench.balances, (row) => row.value), [976, 88, 1064]);
  assert.match(workbench.meta, /供应链快照/);
  assert.match(workbench.tableRows, /UNKNOWN-SKU.*未匹配.*VALID-SKU.*已计入/);
  assert.match(evaluate("scMetricDashboard('wip')").stage, /211 件/);
});
