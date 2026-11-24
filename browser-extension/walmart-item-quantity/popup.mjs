import { readCurrentItem, summarizeQuantity } from './item-reader.mjs';
import { filterVariants, variantTsv, parseHistoricalCsv } from './variants.mjs';
import { initScanUi } from './scan-ui.mjs';

const byId = id => document.getElementById(id);
const set = (id, value) => { byId(id).textContent = value; };
const raw = value => value === undefined ? '未返回' : value === null ? 'null' : String(value);
const scanUi = chrome.runtime?.sendMessage && chrome.storage?.onChanged ? initScanUi(chrome, document, navigator.clipboard, message => window.confirm(message)) : null;
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

function showError(message) {
  byId('status').dataset.state = 'error';
  set('status', message);
}

function render(item) {
  const { amount, reason } = summarizeQuantity(item);
  const observedAt = new Date().toISOString();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '未知时区';
  const availability = item.availability === 'IN_STOCK' ? '有货' : item.availability === 'OUT_OF_STOCK' ? '缺货' : item.availability || '状态未返回';
  set('availability', availability);
  byId('availability').dataset.state = item.availability === 'IN_STOCK' ? 'in-stock' : item.availability === 'OUT_OF_STOCK' ? 'out-of-stock' : 'unknown';
  set('quantity', amount === null ? '—' : `${new Intl.NumberFormat('zh-CN').format(amount)} 件`);
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
  byId('result').hidden = false;
  byId('status').hidden = true;

  const variants = Array.isArray(item.variants) ? item.variants : [];
  let historical = new Map();
  const inStock = variants.filter(row => row.status === 'IN_STOCK').length;
  const outOfStock = variants.filter(row => row.status === 'OUT_OF_STOCK').length;
  const issues = (item.duplicateCount || 0) + (item.invalidCount || 0);
  set('variant-count', variants.length
    ? `本页返回 ${variants.length} 个 Item：有货 ${inStock}，缺货 ${outOfStock}，其他 ${variants.length - inStock - outOfStock}。${issues ? `另有 ${issues} 条重复或无效记录未列入。` : ''}`
    : '本页未返回可用的变体列表，无法判断整款数量。');
  function updateVariants() {
    const visible = filterVariants(variants, byId('variant-search').value || '', byId('variant-status').value || 'ALL');
    set('variant-filter-count', visible.length ? `当前筛选 ${visible.length} 条。` : '没有匹配的变体，请换个条件。');
    byId('variant-list').value = variantTsv(visible, item.itemId, amount, historical);
    return visible;
  }
  byId('variant-search').addEventListener('input', updateVariants);
  byId('variant-status').addEventListener('change', updateVariants);
  byId('historical-file').addEventListener('change', async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      if (file.size > 5_000_000) throw new Error('文件超过 5 MB。');
      const parsed = parseHistoricalCsv(await file.text());
      historical = parsed.result;
      const matches = variants.filter(row => historical.has(row.itemId)).length;
      set('historical-status', `已匹配 ${matches} 个 Item；采集时间以原文件为准，未确认时视为未知。${parsed.duplicateCount ? ` ${parsed.duplicateCount} 个重复 ID 已排除。` : ''}历史值不代表当前数量。`);
      updateVariants();
    } catch (error) {
      historical = new Map();
      set('historical-status', `导入失败：${error.message} 请使用包含 usItemId、orderLimit 的 CSV。`);
      updateVariants();
    }
  });
  updateVariants();
  byId('copy-variants').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(variantTsv(updateVariants(), item.itemId, amount, historical));
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
  if (tab?.id == null || url?.protocol !== 'https:' || !/^(?:www\.)?walmart\.com$/i.test(url.hostname) || !/^\/ip\/(?:[^/]+\/)?[a-z0-9]+\/?$/i.test(url.pathname)) {
    showError(errors['unsupported-page']);
    return;
  }
  const [injection] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: readCurrentItem });
  const item = injection?.result;
  if (!item || item.error) {
    showError(errors[item?.error] ?? errors['no-page-data']);
    return;
  }
  scanUi?.setPage(tab.id, item.variants || []);
  render(item);
}

run().catch(() => showError('无法读取当前标签页。请在已加载的 Walmart 商品页重试。'));
