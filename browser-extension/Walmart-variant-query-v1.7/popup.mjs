import { readCurrentItem, summarizeQuantity } from './item-reader.mjs';
import { filterVariants, variantTsv, parseHistoricalCsv } from './variants.mjs';
import { initScanUi } from './scan-ui.mjs';
import { initBulkUi } from './bulk-ui.mjs';

const byId = id => document.getElementById(id);
const set = (id, value) => { byId(id).textContent = value; };
const nf = new Intl.NumberFormat('zh-CN');
const raw = value => value === undefined ? '未返回' : value === null ? 'null' : String(value);
const scanUi = chrome.runtime?.sendMessage && chrome.storage?.onChanged ? initScanUi(chrome, document, navigator.clipboard, message => window.confirm(message)) : null;
const bulkUi = chrome.runtime?.sendMessage ? initBulkUi(chrome, document, navigator.clipboard, message => window.confirm(message)) : null;
const reasons = {
  match: '两个前台限购字段一致。',
  'order-only': '按前台返回的 orderLimit 显示。',
  'max-only': '按前台返回的 maxOrderQuantity 显示。',
  conflict: '两个限购字段不一致，无法确认单一件数。',
  'not-returned': '本页没有返回可用的限购数字。',
  'no-shipping': '本页未返回 SHIPPING 发货选项。',
  'multiple-shipping': '本页返回多条 SHIPPING 选项，无法确定对应的限购量。',
  'out-of-stock-limit': '页面标记缺货，不能把同时返回的限购数字当作可下单件数。',
};
const errors = {
  'unsupported-page': '请在当前标签页打开 Walmart 美国站的具体商品页，再点插件。',
  'item-mismatch': '页面数据的 Item ID 和地址栏不一致。切换变体后，请等页面加载，必要时手动刷新，再打开插件。',
  'variant-mismatch': '页面已选颜色或尺码与 Item 数据不一致。请等页面加载，必要时手动刷新，再打开插件。',
  'no-page-data': '当前页没有可读的商品数据。请确认商品页已正常加载，再打开插件。',
  'invalid-page-data': '商品页数据格式无法读取。当前 Item 的件数未确认。',
};

function initModeTabs() {
  const tabs = ['bulk-tab', 'item-tab'].map(byId);
  function select(selected) {
    for (const tab of tabs) {
      const active = tab === selected;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
      byId(tab.getAttribute('aria-controls')).hidden = !active;
    }
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => select(tab));
    tab.addEventListener('keydown', event => {
      const destinations = {
        ArrowLeft: (index + tabs.length - 1) % tabs.length,
        ArrowRight: (index + 1) % tabs.length,
        Home: 0,
        End: tabs.length - 1,
      };
      if (!Object.hasOwn(destinations, event.key)) return;
      event.preventDefault();
      const next = tabs[destinations[event.key]];
      select(next);
      next.focus();
    });
  });
  select(tabs[0]);
}

function showError(message, itemOnly = false) {
  for (const id of ['status', 'item-status']) {
    const status = byId(id);
    if (id === 'status' && itemOnly) {
      status.hidden = true;
      continue;
    }
    status.dataset.state = 'error';
    status.textContent = message;
    status.hidden = false;
  }
}

function stockLabel(status) {
  if (status === 'IN_STOCK') return '有货';
  if (status === 'OUT_OF_STOCK') return '缺货';
  return status || '状态未返回';
}

function stockState(status) {
  if (status === 'IN_STOCK') return 'in-stock';
  if (status === 'OUT_OF_STOCK') return 'out-of-stock';
  return 'unknown';
}

function renderVariantRow(variant, currentId, amount, historical, showHistory) {
  const row = document.createElement('tr');
  const isCurrent = variant.itemId === currentId;
  const knownLimit = isCurrent && amount !== null;
  const prior = historical.get(variant.itemId);
  if (isCurrent) {
    row.dataset.current = 'true';
    row.setAttribute('aria-current', 'true');
  }

  function cell(value, className = '') {
    const data = document.createElement('td');
    data.textContent = value ?? '—';
    data.className = className;
    row.append(data);
    return data;
  }

  cell(variant.color || '—');
  cell(variant.size || '—');
  cell(variant.itemId, 'mono');
  cell(stockLabel(variant.status)).dataset.state = stockState(variant.status);
  cell(knownLimit ? nf.format(amount) : '未取得', knownLimit ? 'num' : 'num dim');
  cell(variant.price == null ? '—' : `${variant.price}${variant.currency ? ` ${variant.currency}` : ''}`, 'num');
  if (showHistory) {
    cell(prior ? nf.format(prior.limit) : '—', 'num');
    cell(prior?.status ? stockLabel(prior.status) : '—');
  }
  cell(variant.productId || '—', 'mono');
  return row;
}

function render(item) {
  const { amount, reason } = summarizeQuantity(item);
  const observedAt = new Date().toISOString();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '未知时区';
  set('availability', stockLabel(item.availability));
  byId('availability').dataset.state = stockState(item.availability);
  set('quantity', amount === null ? '—' : `${nf.format(amount)} `);
  if (amount !== null) {
    const unit = document.createElement('span');
    unit.className = 'hero-unit';
    unit.textContent = '件';
    byId('quantity').append(unit);
  }
  const top = item.topOrderLimit;
  const shipping = item.fields.orderLimit;
  const bothNumeric = value => (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) || (typeof value === 'string' && /^\d+$/.test(value.trim()));
  const topDisagrees = bothNumeric(top) && bothNumeric(shipping) && Number(top) !== Number(shipping);
  set('quantity-note', reasons[reason] + (topDisagrees ? ' 顶层与发货 orderLimit 不一致，请查看原始字段。' : ''));
  set('item-id', item.itemId);
  set('product-id', item.productId || '未返回');
  set('color', item.color || '未返回');
  set('size', item.size || '未返回');
  set('seller', item.seller || '未返回');
  set('price', item.price == null ? raw(item.price) : `${item.price} ${item.currency || '币种未返回'}`);
  set('observed-at', `${new Date(observedAt).toLocaleString('zh-CN')} (${zone})`);
  set('top-order-limit', raw(item.topOrderLimit));
  set('order-limit', raw(item.fields.orderLimit));
  set('max-order-quantity', raw(item.fields.maxOrderQuantity));
  set('available-quantity', raw(item.fields.availableQuantity));
  set('page-status', item.pageStatus || '未返回');
  const fulfillment = item.shippingCount === 1 ? 'SHIPPING' : item.shippingCount > 1 ? 'SHIPPING 不唯一' : '未返回';
  set('fulfillment', fulfillment);
  set('seller-id', item.sellerId || '未返回');
  set('offer-id', item.offerId || '未返回');
  set('selected-tags', item.selectedTags?.join(' / ') || '未返回');
  if (item.itemId) {
    set('head-item', String(item.itemId));
    byId('head-item').hidden = false;
    byId('head-item').closest('.head-context').hidden = false;
  }
  byId('result').hidden = false;
  byId('status').hidden = true;
  byId('item-status').hidden = true;

  const variants = Array.isArray(item.variants) ? item.variants : [];
  const tableWrap = byId('variant-table-wrap');
  const tableBody = byId('variant-list');
  const historyHeaders = tableWrap.querySelectorAll('thead [data-history-column]');
  const copyVariants = byId('copy-variants');
  let historical = new Map();
  let showHistory = false;
  let importVersion = 0;
  let visible = [];
  const inStock = variants.filter(row => row.status === 'IN_STOCK').length;
  const outOfStock = variants.filter(row => row.status === 'OUT_OF_STOCK').length;
  const issues = (item.duplicateCount || 0) + (item.invalidCount || 0);
  set('variant-count', variants.length
    ? `共 ${variants.length} 个变体 · 有货 ${inStock} · 缺货 ${outOfStock} · 其他 ${variants.length - inStock - outOfStock}${issues ? `。已略过 ${issues} 条重复或无效记录。` : ''}`
    : '');
  function updateVariants() {
    visible = filterVariants(variants, byId('variant-search').value, byId('variant-status').value);
    const hasVisible = visible.length > 0;
    const empty = byId('variant-empty');
    empty.hidden = hasVisible;
    tableWrap.hidden = !hasVisible;
    copyVariants.disabled = !hasVisible;
    set('variant-copy-status', '');
    set('variant-filter-count', variants.length ? `显示 ${visible.length} / ${variants.length} 条` : '');
    empty.textContent = variants.length ? '没有匹配的变体。试试其他关键词或状态。' : '当前商品页没有返回可用的变体列表。';
    for (const header of historyHeaders) header.hidden = !showHistory;
    const rows = document.createDocumentFragment();
    for (const variant of visible) rows.append(renderVariantRow(variant, item.itemId, amount, historical, showHistory));
    tableBody.replaceChildren(rows);
  }
  byId('variant-search').addEventListener('input', updateVariants);
  byId('variant-status').addEventListener('change', updateVariants);
  byId('historical-file').addEventListener('change', async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    const currentImport = ++importVersion;
    const status = byId('historical-status');
    status.dataset.state = 'loading';
    status.textContent = '正在读取历史 CSV…';
    try {
      if (file.size > 5_000_000) throw new Error('文件超过 5 MB。');
      const parsed = parseHistoricalCsv(await file.text());
      if (currentImport !== importVersion) return;
      historical = parsed.result;
      showHistory = true;
      const matches = variants.filter(row => historical.has(row.itemId)).length;
      status.dataset.state = 'success';
      status.textContent = `已匹配 ${matches} / ${variants.length} 个变体。采集时间以原文件为准。${parsed.duplicateCount ? `已排除 ${parsed.duplicateCount} 个重复 ID。` : ''}`;
      updateVariants();
    } catch (error) {
      if (currentImport !== importVersion) return;
      historical = new Map();
      showHistory = false;
      status.dataset.state = 'error';
      status.textContent = `导入失败。${error.message}`;
      updateVariants();
    }
  });
  updateVariants();
  copyVariants.addEventListener('click', async () => {
    if (!visible.length) return;
    try {
      await navigator.clipboard.writeText(variantTsv(visible, item.itemId, amount, historical));
      set('variant-copy-status', '已复制筛选结果，可粘贴到表格。');
    } catch {
      set('variant-copy-status', '复制失败，可选中明细手动复制。');
    }
  });

  byId('copy').addEventListener('click', async () => {
    const column = value => {
      const safe = String(value).replace(/[\t\r\n]+/g, ' ').trim();
      return /^[=+\-@]/.test(safe) ? "'" + safe : safe;
    };
    const fields = [item.color, item.size, item.itemId, amount ?? '', item.availability, raw(item.fields.orderLimit), raw(item.fields.maxOrderQuantity), raw(item.fields.availableQuantity), item.productId ?? '', item.seller ?? '', item.sellerId ?? '', item.offerId ?? '', raw(item.topOrderLimit), item.pageStatus ?? '', raw(item.price), item.currency ?? '', observedAt, zone, fulfillment];
    try {
      await navigator.clipboard.writeText(fields.map(column).join('\t'));
      set('copy-status', '已复制当前 Item。');
    } catch {
      set('copy-status', '浏览器未允许复制，可直接选中所需文字。');
    }
  });
}

async function run() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = tab?.url ? new URL(tab.url) : null;
  const urlId = url?.pathname.match(/^\/ip\/(?:[^/]+\/)?([a-z0-9]+)\/?$/i)?.[1];
  if (tab?.id == null || url?.protocol !== 'https:' || !/^(?:www\.)?walmart\.com$/i.test(url.hostname) || !urlId) {
    showError(errors['unsupported-page']);
    return;
  }
  const numericId = /^\d+$/.test(urlId);
  bulkUi?.setPage(tab.id, numericId ? urlId : undefined, numericId ? undefined : urlId);
  byId('status').hidden = true;
  const [injection] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: readCurrentItem });
  const item = injection?.result;
  if (!item || item.error) {
    showError(errors[item?.error] ?? errors['no-page-data'], true);
    return;
  }
  bulkUi?.setPage(tab.id, item.itemId, item.productId);
  scanUi?.setPage(tab.id, item.variants || []);
  render(item);
}

initModeTabs();
run().catch(() => showError('无法读取当前标签页。请在已加载的 Walmart 商品页重试。', byId('status').hidden));
