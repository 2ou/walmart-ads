import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate } from 'node:timers/promises';
import { initScanUi } from './scan-ui.mjs';

test('controls start, pause, stop and copy with preserved raw values', async () => {
  const els = new Map(['start','pause','resume','stop','copy','status'].map(k => [`scan-${k}`, { disabled: true, textContent: '', handlers: {}, addEventListener(type, f) { this.handlers[type] = f; } }]));
  const doc = { getElementById: id => els.get(id) };
  const messages = []; let listener; let state;
  const api = { runtime: { sendMessage: async message => { messages.push(message); return { ok: true, state }; } }, storage: { onChanged: { addListener: fn => { listener = fn; } } } };
  const copied = []; const ui = initScanUi(api, doc, { writeText: async value => copied.push(value) }, () => true);
  ui.setPage(7, [{ itemId: '100' }]); await setImmediate();
  assert.equal(els.get('scan-start').disabled, false);
  els.get('scan-start').handlers.click(); await setImmediate();
  assert.equal(messages.at(-1).type, 'scan-start');
  assert.equal(messages.at(-1).tabId, 7);
  state = { phase: 'waiting', queue: ['100'], results: [{ itemId: '100', topOrderLimit: 5, availableQuantity: 'null' }] };
  listener({ variantScan: { newValue: state } }, 'local');
  assert.match(els.get('scan-status').textContent, /等待中：1\/1/);
  assert.equal(els.get('scan-pause').disabled, false);
  await els.get('scan-copy').handlers.click();
  assert.match(copied[0], /itemId/);
  assert.match(copied[0], /100/);
});
