import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { setImmediate } from 'node:timers/promises';

const html = readFileSync(new URL('./popup.html', import.meta.url), 'utf8');
let iteration = 0;

async function withPopup(url, result, check) {
  const controls = new Map([...html.matchAll(/\bid="([^"]+)"/g)].map(([, id]) => [id, {
    hidden: id === 'result', dataset: {}, textContent: '', handlers: {},
    addEventListener(event, callback) { this.handlers[event] = callback; },
  }]));
  const injected = [];
  const clipboard = [];
  const mocks = {
    document: { getElementById: id => controls.get(id) },
    chrome: {
      tabs: { query: async () => [{ id: 7, url }] },
      scripting: { executeScript: async request => { injected.push(request); return [{ result }]; } },
    },
    navigator: { clipboard: { writeText: async text => { clipboard.push(text); } } },
  };
  const originals = Object.keys(mocks).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]);
  try {
    for (const [key, value] of Object.entries(mocks)) Object.defineProperty(globalThis, key, { configurable: true, value });
    await import(`./popup.mjs?test=${++iteration}`);
    await setImmediate();
    await check({ controls, injected, clipboard });
  } finally {
    for (const [key, descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
}

test('popup renders and copies exactly the current Item with one isolated script execution', async () => {
  await withPopup('https://www.walmart.com/ip/example/14277256777', {
    itemId: '14277256777', color: '01-Black', size: 'L', shippingCount: 1,
    availability: 'IN_STOCK', fields: { orderLimit: 408, maxOrderQuantity: 408, availableQuantity: null },
  }, async ({ controls, injected, clipboard }) => {
    assert.equal(injected.length, 1);
    assert.deepEqual(injected[0].target, { tabId: 7 });
    assert.equal(controls.get('status').hidden, true);
    assert.equal(controls.get('result').hidden, false);
    assert.equal(controls.get('quantity').textContent, '408 件');
    assert.equal(controls.get('available-quantity').textContent, 'null');
    await controls.get('copy').handlers.click();
    const columns = clipboard[0].split('\t');
    assert.deepEqual(columns.slice(0, 8), ['01-Black', 'L', '14277256777', '408', 'IN_STOCK', '408', '408', 'null']);
    assert.equal(columns.length, 19);
    assert.match(columns[16], /^\d{4}-\d\d-\d\dT/);
    assert.equal(columns[18], 'SHIPPING');
  });
});

test('popup distinguishes the product and shipping limits and warns when they differ', async () => {
  await withPopup('https://www.walmart.com/ip/6PQ7Z0ISEFIE', {
    itemId: '14277256777', productId: '6PQ7Z0ISEFIE', seller: 'MINTREUS', color: 'Black', size: 'L', shippingCount: 1,
    availability: 'IN_STOCK', topOrderLimit: 77, fields: { orderLimit: 408, maxOrderQuantity: 408 },
  }, ({ controls, injected }) => {
    assert.equal(injected.length, 1);
    assert.equal(controls.get('top-order-limit').textContent, '77');
    assert.equal(controls.get('order-limit').textContent, '408');
    assert.match(controls.get('quantity-note').textContent, /不一致/);
    assert.equal(controls.get('seller').textContent, 'MINTREUS');
  });
});

test('popup does not inject into a non-product tab', async () => {
  await withPopup('https://www.walmart.com.evil.example/ip/14277256777', null, ({ controls, injected }) => {
    assert.equal(injected.length, 0);
    assert.equal(controls.get('status').dataset.state, 'error');
    assert.equal(controls.get('result').hidden, true);
  });
});

test('popup does not display or copy stale variant data', async () => {
  await withPopup('https://www.walmart.com/ip/16957755597', { error: 'item-mismatch', itemId: '16957755597' }, ({ controls, clipboard }) => {
    assert.match(controls.get('status').textContent, /Item ID 和地址栏不一致/);
    assert.equal(controls.get('result').hidden, true);
    assert.equal(controls.get('copy').handlers.click, undefined);
    assert.deepEqual(clipboard, []);
  });
});

test('whole-product view filters and copies without inventing quantities for other items', async () => {
  await withPopup('https://www.walmart.com/ip/example/14277256777', {
    itemId: '14277256777', color: 'Black', size: 'L', shippingCount: 1,
    availability: 'IN_STOCK', fields: { orderLimit: 408, maxOrderQuantity: 408 },
    variants: [
      { itemId: '14277256777', color: 'Black', size: 'L', status: 'IN_STOCK' },
      { itemId: '16957755597', color: 'Green', size: 'XL', status: 'IN_STOCK' },
    ],
  }, async ({ controls, clipboard }) => {
    assert.match(controls.get('variant-count').textContent, /2 个 Item/);
    assert.match(controls.get('variant-list').value, /Green\tXL\t16957755597.*未取得/);
    controls.get('variant-search').value = 'Green';
    controls.get('variant-search').handlers.input();
    await controls.get('copy-variants').handlers.click();
    assert.match(clipboard[0], /Green\tXL\t16957755597/);
    assert.doesNotMatch(clipboard[0], /14277256777/);
  });
});
