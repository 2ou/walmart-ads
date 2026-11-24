import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout } from 'node:timers/promises';

test('scan waits, processes variants sequentially, pauses and stops on navigation failure', async () => {
  const listeners = {}; const data = {}; const scheduled = []; const navigated = []; const timers = [];
  const event = name => ({ addListener: fn => { listeners[name] = fn; } });
  let injection;
  const api = {
    runtime: { onMessage: event('message') },
    alarms: { onAlarm: event('alarm'), create: async (name, options) => scheduled.push({ name, ...options }), clear: async () => true },
    storage: { local: { get: async key => ({ [key]: data[key] }), set: async object => Object.assign(data, structuredClone(object)) } },
    tabs: { onUpdated: event('updated'), onRemoved: event('removed'), get: async () => ({ url: 'https://www.walmart.com/ip/100' }), update: async (id, options) => navigated.push(options.url) },
    scripting: { executeScript: async () => [{ result: injection }] },
  };
  Object.defineProperty(globalThis, 'chrome', { configurable: true, value: api });
  const originalTimer = globalThis.setTimeout;
  globalThis.setTimeout = (callback, delay) => { timers.push({ callback, delay }); return 0; };
  const message = content => new Promise(resolve => listeners.message(content, {}, resolve));
  const settle = async () => { await setTimeout(10); };
  try {
    const scanner = await import('./scanner.mjs?test=1');
    assert.equal(scanner.randomDelayMs(() => 0), 1000);
    assert.equal(scanner.randomDelayMs(() => 0.9999), 20000);
    const started = await message({ type: 'scan-start', tabId: 7, variants: [{ itemId: '100' }, { itemId: '200' }] });
    assert.equal(started.state.phase, 'waiting');
    assert.equal(navigated.length, 0);
    assert.ok(scheduled[0].when - Date.now() > 29000);
    assert.ok(timers[0].delay >= 1000 && timers[0].delay <= 20000);
    listeners.alarm({ name: 'variantScanNext' }); await settle();
    assert.deepEqual(navigated, ['https://www.walmart.com/ip/100']);
    injection = { itemId: '100', productId: 'ABC', color: 'Black', size: 'L', availability: 'IN_STOCK', fields: { orderLimit: 5, availableQuantity: null } };
    listeners.updated(7, { status: 'complete' }, { url: 'https://www.walmart.com/ip/100' }); await settle();
    assert.equal(data.variantScan.results.length, 1);
    assert.equal(data.variantScan.results[0].maxOrderQuantity, '未返回');
    assert.equal(data.variantScan.results[0].availableQuantity, 'null');
    assert.equal((await message({ type: 'scan-pause' })).state.phase, 'paused');
    listeners.alarm({ name: 'variantScanNext' }); await settle();
    assert.equal(navigated.length, 1);
    assert.equal((await message({ type: 'scan-resume' })).state.phase, 'waiting');
    listeners.alarm({ name: 'variantScanNext' }); await settle();
    assert.equal(navigated.length, 2);
    listeners.updated(7, { status: 'complete' }, { url: 'https://www.walmart.com/blocked' }); await settle();
    assert.equal(data.variantScan.phase, 'stopped');
    assert.equal(data.variantScan.results.length, 1);
    assert.match(data.variantScan.reason, /异常/);
    const replacement = await message({ type: 'scan-start', tabId: 7, variants: [{ itemId: '300' }] });
    assert.equal(replacement.ok, false);
  } finally { globalThis.setTimeout = originalTimer; delete globalThis.chrome; }
});
