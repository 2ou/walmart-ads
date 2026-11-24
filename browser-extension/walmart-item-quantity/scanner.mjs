import { readCurrentItem } from './item-reader.mjs';

const KEY = 'variantScan';
const ALARM = 'variantScanNext';
let busy = false;
let tickBusy = false;
let timer;
export function randomDelayMs(random = Math.random) { return (Math.floor(random() * 20) + 1) * 1000; }
async function load() { return (await chrome.storage.local.get(KEY))[KEY]; }
async function save(state) { await chrome.storage.local.set({ [KEY]: state }); }
async function schedule() {
  clearTimeout(timer);
  // The alarm is a recovery fallback when the service worker is suspended.
  await chrome.alarms.create(ALARM, { when: Date.now() + 30000 });
  timer = setTimeout(() => { void advance(); }, randomDelayMs());
}
async function watchdog() {
  clearTimeout(timer);
  await chrome.alarms.create(ALARM, { when: Date.now() + 30000 });
}
async function halt(state, reason, phase = 'stopped') {
  clearTimeout(timer); state.phase = phase; state.reason = reason; await chrome.alarms.clear(ALARM); await save(state);
}

chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  (async () => {
    let state = await load();
    if (message?.type === 'scan-start') {
      if (state?.phase === 'waiting' || state?.phase === 'navigating') throw new Error('已有任务正在运行，请先停止。');
      if (state?.results?.length && message.replace !== true) throw new Error('已有结果，请先复制并确认覆盖。');
      if (!Number.isInteger(message.tabId) || !Array.isArray(message.variants) || !message.variants.length || message.variants.length > 1000) throw new Error('没有可用的变体列表。');
      const tab = await chrome.tabs.get(message.tabId);
      const currentUrl = new URL(tab.url || '');
      const origin = currentUrl.origin;
      if (!/^https:\/\/(?:www\.)?walmart\.com$/.test(origin)) throw new Error('请从 Walmart 商品页启动。');
      if (!currentUrl.pathname.startsWith('/ip/')) throw new Error('请从商品详情页启动。');
      const ids = [...new Set(message.variants.map(v => String(v.itemId)).filter(v => /^\d+$/.test(v)))];
      if (!ids.length) throw new Error('没有可用的 Item ID。');
      state = { phase: 'waiting', reason: '', tabId: message.tabId, origin, lastPath: currentUrl.pathname, queue: ids, index: 0, results: [], startedAt: new Date().toISOString() };
      await save(state); await schedule();
    } else if (message?.type === 'scan-stop' && state) {
      await halt(state, '用户已停止。');
    } else if (message?.type === 'scan-pause' && state && ['waiting', 'navigating'].includes(state.phase)) {
      await halt(state, '用户已暂停。', 'paused');
    } else if (message?.type === 'scan-resume' && state?.phase === 'paused') {
      state.phase = 'waiting'; state.reason = ''; await save(state); await schedule();
    }
    respond({ ok: true, state: await load() });
  })().catch(error => respond({ ok: false, error: error.message }));
  return true;
});

async function advance() {
  if (tickBusy) return;
  tickBusy = true;
  try {
    const state = await load();
    if (!state) return;
    if (state.phase === 'navigating') return halt(state, '页面加载超时或未完成，任务已停止。');
    if (state.phase !== 'waiting') return;
    if (state.index >= state.queue.length) return halt(state, '全部已完成。', 'complete');
    const tab = await chrome.tabs.get(state.tabId);
    const current = new URL(tab.url || '');
    if (current.origin !== state.origin || current.pathname !== state.lastPath) return halt(state, '商品标签页已被切换，任务已停止。');
    state.phase = 'navigating'; state.expected = state.queue[state.index];
    await save(state);
    await watchdog();
    const latest = await load();
    if (latest?.phase !== 'navigating' || latest.expected !== state.expected) return;
    await chrome.tabs.update(state.tabId, { url: `${state.origin}/ip/${state.expected}` });
  } catch (error) { const state = await load(); if (state) await halt(state, `无法继续：${error.message}`); }
  finally { tickBusy = false; }
}
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === ALARM) void advance();
});

chrome.tabs.onUpdated.addListener((tabId, change, tab) => {
  if (change.status !== 'complete' || busy) return;
  busy = true;
  (async () => {
    const state = await load();
    if (!state || state.phase !== 'navigating' || tabId !== state.tabId) return;
    const url = new URL(tab.url || '');
    const id = url.pathname.match(/^\/ip\/(?:[^/]+\/)?(\d+)\/?$/i)?.[1];
    if (url.origin !== state.origin || id !== state.expected) return halt(state, '页面跳转异常，可能需要验证或拒绝访问；任务已停止。');
    const [injection] = await chrome.scripting.executeScript({ target: { tabId }, func: readCurrentItem });
    const item = injection?.result;
    if (!item || item.error || item.itemId !== state.expected) return halt(state, `页面读取异常（${item?.error || 'Item 不匹配'}），任务已停止。`);
    const latest = await load();
    if (!latest || latest.phase !== 'navigating' || latest.expected !== state.expected) return;
    const raw = value => value === undefined ? '未返回' : value === null ? 'null' : value;
    latest.results.push({ itemId: item.itemId, productId: item.productId, color: item.color, size: item.size, status: item.availability, topOrderLimit: raw(item.topOrderLimit), shippingOrderLimit: raw(item.fields.orderLimit), maxOrderQuantity: raw(item.fields.maxOrderQuantity), availableQuantity: raw(item.fields.availableQuantity), capturedAt: new Date().toISOString() });
    latest.index++; latest.lastPath = url.pathname;
    if (latest.index >= latest.queue.length) return halt(latest, '全部已完成。', 'complete');
    latest.phase = 'waiting'; latest.expected = ''; await save(latest); await schedule();
  })().catch(async error => { const state = await load(); if (state) await halt(state, `页面读取失败：${error.message}`); }).finally(() => { busy = false; });
});

chrome.tabs.onRemoved.addListener(tabId => {
  (async () => { const state = await load(); if (state?.tabId === tabId && ['waiting', 'navigating'].includes(state.phase)) await halt(state, '商品标签页已关闭。'); })();
});
