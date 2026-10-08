const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const workspace = path.resolve(__dirname, '..');
const reportPath = path.join(
  workspace,
  'artifacts',
  'ads-analysis-2026-08-20',
  'PNK近60天广告优化报告_2026-08-20.html'
);
const mysqlPath = 'D:\\Tools\\MySQL\\MySQL Server 9.0\\bin\\mysql.exe';

const html = fs.readFileSync(reportPath, 'utf8');
const dataOpen = '<script id="report-data" type="application/json">';
const dataStart = html.indexOf(dataOpen);
const runtimeStart = html.indexOf('const DATA=', dataStart + dataOpen.length);
const dataEnd = html.lastIndexOf('</script>', runtimeStart);
if (dataStart < 0 || runtimeStart < 0 || dataEnd < 0) throw new Error('未找到完整的 report-data JSON');

function findJsonObjectEnd(text, start) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < text.length; index += 1) {
    const char = text[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') inString = true;
    else if (char === '{') depth += 1;
    else if (char === '}' && --depth === 0) return index + 1;
  }
  return -1;
}

// 兼容修复早期字符串替换把金额中的 $1 / $2 / $3 误识别为捕获组的问题。
let rawData = html.slice(dataStart + dataOpen.length, dataEnd);
const embeddedOriginalStart = rawData.indexOf('{"generatedAt"', 1);
if (embeddedOriginalStart >= 0) {
  const embeddedOriginalEnd = findJsonObjectEnd(rawData, embeddedOriginalStart);
  if (embeddedOriginalEnd < 0) throw new Error('无法恢复被误插入的原始报告JSON');
  const embeddedOriginal = rawData.slice(embeddedOriginalStart, embeddedOriginalEnd);
  JSON.parse(embeddedOriginal);
  rawData = rawData.replaceAll(embeddedOriginal, '$2');
}
rawData = rawData
  .replaceAll(dataOpen, '$1')
  .replaceAll('</script>', '$3');
let report;
try {
  report = JSON.parse(rawData);
} catch (error) {
  const position = Number((String(error.message).match(/position (\d+)/) || [])[1] || 0);
  console.error(error.message);
  console.error(rawData.slice(Math.max(0, position - 160), position + 160));
  process.exit(1);
}
const query = `
SELECT DATE_FORMAT(MAX(inventory.snapshot_at), '%Y-%m-%d %H:%i:%s.%f')
  FROM wm_ads_inventory_snapshot inventory
  JOIN wm_ads_import_file file ON file.id = inventory.import_file_id AND file.active = true;
SELECT inventory.sku,
       COALESCE(inventory.item_id, ''),
       inventory.available_units,
       inventory.reserved_units,
       inventory.inbound_units,
       inventory.forecasted_days_supply
  FROM wm_ads_inventory_snapshot inventory
  JOIN wm_ads_import_file file ON file.id = inventory.import_file_id AND file.active = true
 WHERE inventory.snapshot_at = (
       SELECT MAX(current_inventory.snapshot_at)
         FROM wm_ads_inventory_snapshot current_inventory
         JOIN wm_ads_import_file current_file
           ON current_file.id = current_inventory.import_file_id
          AND current_file.active = true
 )
 ORDER BY inventory.sku;
`;

const output = execFileSync(
  mysqlPath,
  ['-uroot', '-proot', '-D', 'walmart_ads', '--batch', '--raw', '--skip-column-names', '-e', query],
  { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }
).replace(/^mysql: \[Warning\].*\r?\n/gm, '').trim();

const lines = output.split(/\r?\n/).filter(Boolean);
const snapshotAt = lines.shift();
const inventoryRows = lines.map(line => {
  const [sku, itemId, available, reserved, inbound, daysSupply] = line.split('\t');
  return {
    sku,
    itemId,
    available: Number(available || 0),
    reserved: Number(reserved || 0),
    inbound: Number(inbound || 0),
    daysSupply: Number(daysSupply || 0)
  };
});

const bySku = new Map(inventoryRows.map(row => [row.sku.toLowerCase(), row]));
const byItemId = new Map(inventoryRows.filter(row => row.itemId).map(row => [row.itemId, row]));
const uniqueConfiguredSkus = new Map();
let configuredRows = 0;
let matchedRows = 0;
let effectiveAdjustmentRows = 0;

function stockState(row) {
  if (!row) return { key: 'unknown', label: '未匹配库存', reason: '最新库存快照未找到该 SKU' };
  if (row.available <= 0) return { key: 'zero', label: '0库存', reason: 'WFS可售为0' };
  if (row.available <= 5) return { key: 'low', label: '低库存', reason: `WFS可售仅${row.available}件` };
  if (row.daysSupply > 0 && row.daysSupply < 14) {
    return { key: 'low', label: '低库存', reason: `系统覆盖约${row.daysSupply.toFixed(1)}天` };
  }
  return { key: 'ok', label: '库存正常', reason: row.daysSupply > 0 ? `系统覆盖约${row.daysSupply.toFixed(1)}天` : 'WFS可售高于5件，暂无销量覆盖基线' };
}

function inventoryAdAction(item, state) {
  const campaignPaused = String(item.campaignStatus || '').toLowerCase() === 'paused';
  const alreadyStopped = campaignPaused || !item.enabled || !item.approved || !item.effective;
  if (state.key === 'unknown') return { key: 'verify', label: '先核对库存', detail: '库存未匹配，暂不自动给出调价动作' };
  if (alreadyStopped) return { key: 'stopped', label: '无需重复操作', detail: campaignPaused ? '活动已暂停' : !item.enabled ? 'Item已关闭' : '当前未实际投放' };
  if (state.key === 'zero') return { key: 'pause', label: '暂停Item广告', detail: '0库存，避免继续引流到缺货SKU' };
  if (state.key === 'low') return { key: 'reduce', label: '竞价下调30%', detail: '停止放量，保留小流量观察补货' };
  return { key: 'keep', label: '保持投放', detail: '库存暂不限制广告' };
}

for (const style of report.styles || []) {
  for (const campaign of style.campaigns || []) {
    let zeroCount = 0;
    let lowCount = 0;
    let adjustmentCount = 0;
    for (const item of campaign.configItems || []) {
      configuredRows += 1;
      const inventory = bySku.get(String(item.sku || '').toLowerCase()) || byItemId.get(String(item.itemId || ''));
      if (inventory) matchedRows += 1;
      const state = stockState(inventory);
      const action = inventoryAdAction(item, state);
      item.inventoryAvailable = inventory ? inventory.available : null;
      item.inventoryReserved = inventory ? inventory.reserved : null;
      item.inventoryInbound = inventory ? inventory.inbound : null;
      item.inventoryDaysSupply = inventory ? inventory.daysSupply : null;
      item.inventorySnapshotAt = snapshotAt;
      item.inventoryMatch = inventory ? (inventory.sku.toLowerCase() === String(item.sku || '').toLowerCase() ? 'sku' : 'itemId') : 'none';
      item.stockState = state.key;
      item.stockLabel = state.label;
      item.stockReason = state.reason;
      item.inventoryAdAction = action.key;
      item.inventoryAdActionLabel = action.label;
      item.inventoryAdActionDetail = action.detail;
      if (state.key === 'zero') zeroCount += 1;
      if (state.key === 'low') lowCount += 1;
      if (action.key === 'pause' || action.key === 'reduce') {
        adjustmentCount += 1;
        effectiveAdjustmentRows += 1;
      }
      const skuKey = String(item.sku || item.itemId || '').toLowerCase();
      const current = uniqueConfiguredSkus.get(skuKey);
      if (!current || ['unknown', 'ok', 'low', 'zero'].indexOf(state.key) > ['unknown', 'ok', 'low', 'zero'].indexOf(current.state)) {
        uniqueConfiguredSkus.set(skuKey, { state: state.key, available: inventory ? inventory.available : null });
      }
    }
    campaign.zeroStockConfigItemCount = zeroCount;
    campaign.lowStockConfigItemCount = lowCount;
    campaign.inventoryAdjustmentItemCount = adjustmentCount;
  }
}

const uniqueStockRows = [...uniqueConfiguredSkus.values()];
report.meta.latestInventoryAt = snapshotAt;
report.meta.inventorySnapshotRows = inventoryRows.length;
report.meta.inventoryMatchedConfigurationItems = matchedRows;
report.meta.inventoryUnmatchedConfigurationItems = configuredRows - matchedRows;
report.meta.zeroStockSkuCount = uniqueStockRows.filter(row => row.state === 'zero').length;
report.meta.lowStockSkuCount = uniqueStockRows.filter(row => row.state === 'low').length;
report.meta.inventoryAdjustmentItemCount = effectiveAdjustmentRows;
report.methodology.thresholds = (report.methodology.thresholds || []).filter(text => {
  const value = String(text);
  return !value.startsWith('库存保护：') && !value.startsWith('SKU库存联动：');
});
report.methodology.thresholds.push('SKU库存联动：以最新有效库存快照的WFS可售为准。0库存暂停实际投放的Item广告；低库存（可售1至5件，或系统库存覆盖大于0且少于14天）竞价下调30%并停止放量；活动已暂停或Item已关闭时不重复操作。');
report.sources = (report.sources || []).filter(source => source.name !== '店铺数据库 · 最新库存快照');
report.sources.push({ name: '店铺数据库 · 最新库存快照', rows: inventoryRows.length, updated: snapshotAt.slice(0, 16) });

const nextHtml = html.slice(0, dataStart + dataOpen.length)
  + JSON.stringify(report)
  + html.slice(dataEnd);
fs.writeFileSync(reportPath, nextHtml, 'utf8');

console.log(JSON.stringify({
  reportPath,
  snapshotAt,
  inventoryRows: inventoryRows.length,
  configuredRows,
  matchedRows,
  unmatchedRows: configuredRows - matchedRows,
  zeroStockSkus: report.meta.zeroStockSkuCount,
  lowStockSkus: report.meta.lowStockSkuCount,
  effectiveAdjustmentRows
}, null, 2));
