// 整款速查（v1.5）
// 入口页取 product.variantProductIdMap（全量变体清单）→ 对每个 productId 做同源 fetch
// 拿 SSR HTML → 切 <script id="__NEXT_DATA__"> → 解出 orderLimit。
// 不导航、不渲染页面，所以比逐个打开变体快一个数量级；解析逻辑与命令行 skill 一致。
//
// 该文件同时被 popup（读状态、算汇总）和 service worker（跑批）引用。
// fetchVariantBatch 会被 chrome.scripting.executeScript 序列化注入页面，
// 因此它内部不能引用任何模块作用域的变量或函数。

export const BULK_KEY = 'bulkScan';
export const BULK_ALARM = 'bulkScanNext';
export const BULK_TYPES = new Set(['bulk-start', 'bulk-stop', 'bulk-pause', 'bulk-resume', 'bulk-retry', 'bulk-state']);

const SIZE_ORDER = ['XXS', 'XS', 'S', 'M', 'L', 'XL', '1X', '2X', '3X', '4X', '5X', '2XL', '3XL', '4XL', '5XL', '6X'];

export const CSV_HEADER = ['variant_dim', 'variant_value', 'variant_label', 'size', 'usItemId', 'productId', 'orderLimit', 'maxOrderQuantity', 'orderMinLimit', 'availabilityStatus', 'price', 'wasPrice', 'isPriceReduced', 'seller'];

// 变体维度不一定是 actual_color（实测有 pattern），尺码后缀也不一定存在。
// 通用解析：最后一个 -clothing_size- 之后是尺码，左侧按第一个 - 切「维度名 / 维度值」。
export function parseVariantKey(key) {
  const text = String(key ?? '');
  const SEP = '-clothing_size-';
  const i = text.lastIndexOf(SEP);
  const left = i > 0 ? text.slice(0, i) : text;
  const j = left.indexOf('-');
  const sizeKey = i > 0 ? text.slice(i + SEP.length).toLowerCase() : '';
  return {
    dim: j > 0 ? left.slice(0, j) : 'variant',
    value: j > 0 ? left.slice(j + 1) : left,
    sizeKey,
    size: sizeKey ? sizeKey.toUpperCase() : '?',
  };
}

export function prettify(value) {
  return String(value ?? '').split('-').filter(Boolean).map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

// ── 注入页面：读取入口页的整款清单 ───────────────────────────────────────────
// 必须自包含（会被序列化），返回值只用可结构化克隆的普通对象。
export function readBulkEntry() {
  const text = (value, limit = 120) => typeof value === 'string' ? value.trim().slice(0, limit) : '';
  const num = value => {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value);
    return null;
  };
  if (location.protocol !== 'https:' || !/^(?:www\.)?walmart\.com$/i.test(location.hostname)) return { error: 'unsupported-page' };
  if (!/^\/ip\/(?:[^/]+\/)?[a-z0-9]+\/?$/i.test(location.pathname)) return { error: 'unsupported-page' };
  const element = document.getElementById('__NEXT_DATA__');
  let nextData = null;
  if (element) {
    try { nextData = JSON.parse(element.textContent); } catch { nextData = null; }
  }
  nextData = nextData || window.__NEXT_DATA__;
  const product = nextData?.props?.pageProps?.initialData?.data?.product;
  if (!product?.usItemId) return { error: 'no-page-data' };

  // 展示名：variantCriteria[].variantList[].id -> name。取不到时前端按 slug 美化兜底。
  const labels = {};
  for (const criterion of Array.isArray(product.variantCriteria) ? product.variantCriteria : []) {
    for (const choice of Array.isArray(criterion?.variantList) ? criterion.variantList : []) {
      const id = text(choice?.id, 160);
      const name = text(choice?.name) || text(choice?.displayName);
      if (id && name) labels[id] = name;
    }
  }

  // variantsMap 里带 usItemId / 状态 / 价格，按 productId 建索引，用来给清单预填（仅展示用）。
  const byProductId = {};
  for (const variant of Object.values(product.variantsMap ?? {})) {
    if (!variant) continue;
    const pid = text(variant.id, 80);
    if (!pid) continue;
    byProductId[pid] = {
      itemId: text(variant.usItemId, 40),
      status: text(variant.availabilityStatus, 48),
      price: num(variant.priceInfo?.currentPrice?.price),
    };
  }

  const map = product.variantProductIdMap && typeof product.variantProductIdMap === 'object' ? product.variantProductIdMap : {};
  const items = [];
  for (const [key, pid] of Object.entries(map)) {
    const productId = text(pid, 80);
    if (!key || !productId) continue;
    const known = byProductId[productId] || {};
    items.push({
      key: text(key, 200),
      productId,
      knownItemId: known.itemId || '',
      knownStatus: known.status || '',
      knownPrice: known.price ?? null,
    });
  }
  if (!items.length) return { error: 'no-variant-map' };

  const priceInfo = product.priceInfo || {};
  return {
    origin: location.origin,
    itemId: text(product.usItemId, 40),
    productId: text(product.id, 80),
    name: text(product.name, 240),
    brand: text(product.brand, 80),
    seller: text(product.sellerDisplayName) || text(product.sellerName),
    sellerId: text(product.sellerId, 100),
    rating: num(product.averageRating),
    reviews: num(product.numberOfReviews),
    pagePrice: num(priceInfo.currentPrice?.price),
    labels,
    items,
  };
}

// ── 注入页面：一批变体的 SSR HTML → 字段 ─────────────────────────────────────
// 同源 fetch，与页面自身 XHR 同一条通道；不占标签页、不触发页面渲染。
export async function fetchVariantBatch(args) {
  const input = args && typeof args === 'object' ? args : {};
  const items = Array.isArray(input.items) ? input.items : [];
  const origin = typeof input.origin === 'string' && /^https:\/\/(?:www\.)?walmart\.com$/.test(input.origin) ? input.origin : 'https://www.walmart.com';
  const conc = Math.max(1, Math.min(8, Number(input.conc) || 3));
  const gap = Math.max(0, Math.min(5000, Number(input.gapMs) || 500));
  const timeoutMs = Math.max(5000, Math.min(60000, Number(input.timeoutMs) || 30000));
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const text = (value, limit = 120) => typeof value === 'string' ? value.trim().slice(0, limit) : '';
  const num = value => {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value);
    return null;
  };

  function parseHtml(html) {
    if (typeof html !== 'string' || !html) return null;
    const marker = 'id="__NEXT_DATA__"';
    const at = html.indexOf(marker);
    if (at < 0) return null;
    const start = html.indexOf('>', at) + 1;
    const end = html.indexOf('</script>', start);
    if (start <= 0 || end < 0) return null;
    try {
      const nextData = JSON.parse(html.slice(start, end));
      return nextData?.props?.pageProps?.initialData?.data?.product || null;
    } catch {
      return null;
    }
  }

  async function one(entry) {
    const base = { key: String(entry?.key ?? ''), productId: String(entry?.productId ?? ''), ok: false };
    if (!base.key || !base.productId) return { ...base, error: 'bad-entry' };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(origin + '/ip/' + encodeURIComponent(base.productId), {
        credentials: 'include',
        signal: controller.signal,
        headers: { accept: 'text/html,application/xhtml+xml' },
      });
      const html = await response.text();
      if (!response.ok) return { ...base, status: response.status, error: response.status === 403 ? 'blocked' : response.status === 404 ? 'not-found' : 'http-' + response.status };
      const product = parseHtml(html);
      if (!product) return { ...base, status: response.status, error: html.length < 4000 ? 'empty-page' : 'no-product-data' };
      const priceInfo = product.priceInfo || {};
      const shipping = (Array.isArray(product.fulfillmentOptions) ? product.fulfillmentOptions : []).filter(option => option && option.type === 'SHIPPING');
      const maxOption = shipping.find(option => option.maxOrderQuantity != null);
      return {
        ...base,
        ok: true,
        status: response.status,
        itemId: text(product.usItemId, 40),
        name: text(product.name, 240),
        brand: text(product.brand, 80),
        seller: text(product.sellerDisplayName) || text(product.sellerName),
        orderLimit: num(product.orderLimit),
        orderMinLimit: num(product.orderMinLimit),
        availabilityStatus: text(product.availabilityStatus, 48),
        salesUnit: text(product.salesUnit, 40),
        price: num(priceInfo.currentPrice?.price),
        wasPrice: num(priceInfo.wasPrice?.price),
        isPriceReduced: typeof priceInfo.isPriceReduced === 'boolean' ? priceInfo.isPriceReduced : null,
        shippingCount: shipping.length,
        // 缺货 SKU 没有该字段；缺失≠0，前端按「—」呈现。
        maxOrderQuantity: maxOption ? num(maxOption.maxOrderQuantity) : null,
        categoryPath: text(product.category?.categoryPathId, 120),
        fetchedAt: new Date().toISOString(),
      };
    } catch (error) {
      const aborted = !!error && (error.name === 'AbortError' || /abort/i.test(String(error.message)));
      return { ...base, error: aborted ? 'timeout' : 'fetch-failed' };
    } finally {
      clearTimeout(timer);
    }
  }

  const rows = [];
  for (let i = 0; i < items.length; i += conc) {
    rows.push(...await Promise.all(items.slice(i, i + conc).map(one)));
    if (i + conc < items.length && gap) await sleep(gap);
  }
  return rows;
}

// ── 汇总（popup 侧，也可在 node 里单测）──────────────────────────────────────
export function analyzeBulk(state) {
  const labels = state?.labels && typeof state.labels === 'object' ? state.labels : {};
  const queue = Array.isArray(state?.queue) ? state.queue : [];
  const failures = (Array.isArray(state?.failures) ? state.failures : []).filter(row => row && row.key);
  const seen = new Set();
  const rows = [];
  for (const row of Array.isArray(state?.rows) ? state.rows : []) {
    if (!row || !row.ok || !row.key || seen.has(row.key)) continue;
    seen.add(row.key);
    const parsed = parseVariantKey(row.key);
    rows.push({
      ...row,
      ...parsed,
      label: labels[parsed.dim + '-' + parsed.value] || prettify(parsed.value),
      sizeLabel: labels['clothing_size-' + parsed.sizeKey] || parsed.size,
    });
  }

  const valueOrder = [];
  const orderKeys = Array.isArray(state?.order) && state.order.length ? state.order : queue.map(entry => entry?.key);
  for (const key of orderKeys) {
    const value = parseVariantKey(key).value;
    if (value && !valueOrder.includes(value)) valueOrder.push(value);
  }
  for (const row of rows) if (!valueOrder.includes(row.value)) valueOrder.push(row.value);

  const present = new Set(rows.map(row => row.size));
  const sizes = SIZE_ORDER.filter(size => present.has(size)).concat([...present].filter(size => !SIZE_ORDER.includes(size)).sort());
  const sizeIndex = size => { const at = sizes.indexOf(size); return at < 0 ? 999 : at; };
  rows.sort((a, b) => valueOrder.indexOf(a.value) - valueOrder.indexOf(b.value) || sizeIndex(a.size) - sizeIndex(b.size) || a.size.localeCompare(b.size));

  const number = value => typeof value === 'number' && Number.isFinite(value) ? value : 0;
  const limits = rows.map(row => number(row.orderLimit));
  const sum = limits.reduce((a, b) => a + b, 0);
  const inStock = rows.filter(row => row.availabilityStatus === 'IN_STOCK');
  const outOfStock = rows.filter(row => row.availabilityStatus && row.availabilityStatus !== 'IN_STOCK');
  const unknown = rows.length - inStock.length - outOfStock.length;
  const low = rows.filter(row => number(row.orderLimit) > 0 && number(row.orderLimit) < 10).sort((a, b) => number(a.orderLimit) - number(b.orderLimit));
  const zero = rows.filter(row => row.availabilityStatus === 'IN_STOCK' && number(row.orderLimit) === 0);

  const withMax = rows.filter(row => row.maxOrderQuantity != null);
  const sameMax = withMax.filter(row => number(row.maxOrderQuantity) === number(row.orderLimit)).length;

  const priceCount = new Map();
  for (const row of rows) if (row.price != null) priceCount.set(row.price, (priceCount.get(row.price) || 0) + 1);
  const prices = [...priceCount.entries()].map(([price, count]) => ({ price, count })).sort((a, b) => a.price - b.price);
  const wasCount = new Map();
  for (const row of rows) if (row.wasPrice != null) wasCount.set(row.wasPrice, (wasCount.get(row.wasPrice) || 0) + 1);
  const wasPrices = [...wasCount.entries()].map(([price, count]) => ({ price, count })).sort((a, b) => a.price - b.price);
  const noWas = rows.filter(row => row.wasPrice == null);
  const reduced = rows.filter(row => row.isPriceReduced === true);

  const bySize = sizes.map(size => ({ size, total: rows.filter(row => row.size === size).reduce((a, row) => a + number(row.orderLimit), 0), count: rows.filter(row => row.size === size).length }));
  const values = valueOrder.map(value => {
    const list = rows.filter(row => row.value === value);
    return { value, label: list[0]?.label || prettify(value), total: list.reduce((a, row) => a + number(row.orderLimit), 0), count: list.length };
  });
  const cells = {};
  for (const value of valueOrder) {
    cells[value] = {};
    for (const row of rows.filter(item => item.value === value)) cells[value][row.size] = row;
  }

  const top = rows.slice().sort((a, b) => number(b.orderLimit) - number(a.orderLimit)).slice(0, 5);
  const top5 = top.reduce((a, row) => a + number(row.orderLimit), 0);

  const failureCount = new Map();
  for (const row of failures) failureCount.set(row.error || 'unknown', (failureCount.get(row.error || 'unknown') || 0) + 1);

  return {
    rows,
    failures,
    failureCount: [...failureCount.entries()].map(([reason, count]) => ({ reason, count })),
    dim: rows[0]?.dim || 'variant',
    sizes,
    values,
    cells,
    bySize,
    total: {
      sku: rows.length,
      sum,
      avg: rows.length ? sum / rows.length : 0,
      min: limits.length ? Math.min(...limits) : 0,
      max: limits.length ? Math.max(...limits) : 0,
      top5,
      top5Share: sum ? top5 / sum : 0,
    },
    status: { inStock: inStock.length, outOfStock: outOfStock.length, unknown },
    low,
    zero,
    maxQty: { withField: withMax.length, same: sameMax, conflict: withMax.length - sameMax, missing: rows.length - withMax.length },
    prices,
    wasPrices,
    noWasCount: noWas.length,
    reducedCount: reduced.length,
    expected: Array.isArray(state?.order) && state.order.length ? state.order.length : queue.length,
    fetchedAt: state?.fetchedAt || '',
  };
}

const csvCell = value => {
  const raw = value == null ? '' : String(value);
  return /[",\r\n]/.test(raw) ? '"' + raw.replace(/"/g, '""') + '"' : raw;
};

export function bulkCsv(state) {
  const analysis = analyzeBulk(state);
  const lines = [CSV_HEADER.join(',')];
  for (const row of analysis.rows) {
    lines.push([
      row.dim, row.value, row.label, row.size, row.itemId || '', row.productId || '',
      row.orderLimit ?? '', row.maxOrderQuantity ?? '', row.orderMinLimit ?? '', row.availabilityStatus || '',
      row.price ?? '', row.wasPrice ?? '', row.isPriceReduced === null ? '' : row.isPriceReduced, row.seller || '',
    ].map(csvCell).join(','));
  }
  return '\ufeff' + lines.join('\r\n');
}

export function bulkTsv(state) {
  const analysis = analyzeBulk(state);
  const safe = value => {
    const raw = String(value ?? '').replace(/[\t\r\n]+/g, ' ').trim();
    return /^[=+\-@]/.test(raw) ? "'" + raw : raw;
  };
  const rows = analysis.rows.map(row => [
    row.dim, row.label, row.sizeLabel, row.itemId || '', row.productId || '',
    row.orderLimit ?? '', row.maxOrderQuantity ?? '', row.orderLimit == null ? '' : (row.availabilityStatus || ''),
    row.price ?? '', row.wasPrice ?? '', row.isPriceReduced === null ? '' : (row.isPriceReduced ? '是' : '否'), row.seller || '',
  ]);
  return [[analysis.dim, '维度值', '尺码', 'Item ID', 'Product ID', 'orderLimit', 'maxOrderQuantity', '状态', '价格', '划线价', '降价', '卖家'], ...rows]
    .map(row => row.map(safe).join('\t')).join('\n');
}

// ── service worker：跑批 ────────────────────────────────────────────────────
export function initBulkScanner(chromeApi) {
  const load = async () => (await chromeApi.storage.local.get(BULK_KEY))[BULK_KEY];
  const save = async state => { await chromeApi.storage.local.set({ [BULK_KEY]: state }); };
  let timer = null;
  let ticking = false;

  const clearAlarm = () => chromeApi.alarms.clear(BULK_ALARM);
  const armRecovery = () => chromeApi.alarms.create(BULK_ALARM, { when: Date.now() + 30000 });

  async function halt(state, reason, phase = 'stopped') {
    clearTimeout(timer);
    state.phase = phase;
    state.reason = reason;
    state.updatedAt = new Date().toISOString();
    await clearAlarm();
    await save(state);
  }
  async function complete(state) {
    clearTimeout(timer);
    state.phase = 'complete';
    state.reason = '全部已完成。';
    state.fetchedAt = new Date().toISOString();
    state.updatedAt = state.fetchedAt;
    await clearAlarm();
    await save(state);
  }
  async function schedule(state, delay) {
    clearTimeout(timer);
    await armRecovery();
    timer = setTimeout(() => { void tick(); }, Math.max(0, delay));
  }

  async function tick() {
    if (ticking) return;
    ticking = true;
    try {
      const state = await load();
      if (!state || state.phase !== 'running') return;
      if (state.index >= state.queue.length) return complete(state);
      try { await chromeApi.tabs.get(state.tabId); } catch { return halt(state, '商品标签页已关闭。'); }

      const slice = state.queue.slice(state.index, state.index + state.batchSize);
      const args = [{
        items: slice.map(entry => ({ key: entry.key, productId: entry.productId })),
        origin: state.origin,
        conc: state.conc,
        gapMs: state.gapMs,
        timeoutMs: state.timeoutMs,
      }];
      const run = async world => {
        const [injection] = await chromeApi.scripting.executeScript({ target: { tabId: state.tabId }, func: fetchVariantBatch, args, world });
        return Array.isArray(injection?.result) ? injection.result : [];
      };

      let batch = [];
      let injectionError = null;
      // MAIN world 与页面自身 fetch 完全同一条链路（命令行 skill 验证过的路径）；
      // 若该世界被策略挡住，退到 ISOLATED world 再试一次。
      try {
        batch = await run('MAIN');
      } catch (error) {
        injectionError = error;
      }
      if (!batch.some(row => row?.ok)) {
        try {
          const alt = await run('ISOLATED');
          if (alt.some(row => row?.ok) || !batch.length) batch = alt;
        } catch (error) {
          injectionError = injectionError || error;
        }
      }
      if (!batch.length) {
        await halt(state, `注入页面失败：${injectionError?.message || '页面没有返回结果'}。请刷新商品页后重试。`);
        return;
      }

      const ok = batch.filter(row => row?.ok);
      const bad = batch.filter(row => !row?.ok);
      state.rows.push(...ok);
      state.failures.push(...bad);
      state.index = Math.min(state.queue.length, state.index + slice.length);
      state.updatedAt = new Date().toISOString();
      state.fetchedAt = state.updatedAt;
      state.failStreak = ok.length ? 0 : (state.failStreak || 0) + 1;
      if (state.failStreak >= 3) {
        await halt(state, '连续 3 批都没取到数据，疑似验证页或拒绝访问，任务已停止。可稍后重试失败项。');
        return;
      }
      if (state.index >= state.queue.length) return complete(state);
      state.phase = 'running';
      await save(state);
      await schedule(state, state.gapMs);
    } catch (error) {
      const state = await load();
      if (state) await halt(state, `任务异常：${error?.message || error}`);
    } finally {
      ticking = false;
    }
  }

  chromeApi.runtime.onMessage.addListener((message, _sender, respond) => {
    if (!BULK_TYPES.has(message?.type)) return false;
    (async () => {
      let state = await load();
      if (message.type === 'bulk-start') {
        if (state && ['running', 'paused'].includes(state.phase)) throw new Error('整款速查正在运行，请先停止。');
        const entry = message.entry;
        if (!entry || !Array.isArray(entry.items) || !entry.items.length) throw new Error('入口页没有返回变体清单。');
        if (entry.items.length > 1000) throw new Error('变体数量超过 1000，已拒绝。');
        if (!Number.isInteger(message.tabId)) throw new Error('缺少标签页。');
        const tab = await chromeApi.tabs.get(message.tabId);
        const url = new URL(tab.url || '');
        if (!/^https:\/\/(?:www\.)?walmart\.com$/.test(url.origin) || !url.pathname.startsWith('/ip/')) throw new Error('请从 Walmart 商品详情页启动。');
        state = {
          phase: 'running',
          reason: '',
          tabId: message.tabId,
          origin: entry.origin || url.origin,
          itemId: entry.itemId || '',
          productId: entry.productId || '',
          name: entry.name || '',
          brand: entry.brand || '',
          seller: entry.seller || '',
          rating: entry.rating ?? null,
          reviews: entry.reviews ?? null,
          labels: entry.labels || {},
          queue: entry.items.map(item => ({ key: item.key, productId: item.productId, knownItemId: item.knownItemId || '', knownStatus: item.knownStatus || '', knownPrice: item.knownPrice ?? null })),
          order: entry.items.map(item => item.key),
          index: 0,
          rows: [],
          failures: [],
          failStreak: 0,
          batchSize: Math.max(1, Math.min(12, Number(message.batchSize) || 6)),
          conc: Math.max(1, Math.min(8, Number(message.conc) || 3)),
          gapMs: Math.max(0, Math.min(5000, Number(message.gapMs) || 500)),
          timeoutMs: 30000,
          startedAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          fetchedAt: '',
        };
        await save(state);
        await schedule(state, 0);
      } else if (message.type === 'bulk-stop' && state) {
        await halt(state, '用户已停止。');
      } else if (message.type === 'bulk-pause' && state && state.phase === 'running') {
        await halt(state, '用户已暂停。', 'paused');
      } else if (message.type === 'bulk-resume' && state?.phase === 'paused') {
        state.phase = 'running';
        state.reason = '';
        state.failStreak = 0;
        await save(state);
        await schedule(state, 0);
      } else if (message.type === 'bulk-retry' && state && ['complete', 'stopped', 'paused'].includes(state.phase)) {
        const retry = (state.failures || []).filter(row => row?.key && row?.productId);
        if (!retry.length) throw new Error('没有失败的变体需要重试。');
        state.queue = retry.map(row => ({ key: row.key, productId: row.productId, knownItemId: '', knownStatus: '', knownPrice: null }));
        state.failures = [];
        state.index = 0;
        state.failStreak = 0;
        state.phase = 'running';
        state.reason = '';
        await save(state);
        await schedule(state, 0);
      }
      respond({ ok: true, state: await load() });
    })().catch(error => respond({ ok: false, error: error?.message || String(error) }));
    return true;
  });

  chromeApi.alarms.onAlarm.addListener(alarm => {
    if (alarm.name === BULK_ALARM) void tick();
  });
  chromeApi.tabs.onRemoved.addListener(tabId => {
    (async () => {
      const state = await load();
      if (state?.tabId === tabId && ['running', 'paused'].includes(state.phase)) await halt(state, '商品标签页已关闭。');
    })();
  });
}
