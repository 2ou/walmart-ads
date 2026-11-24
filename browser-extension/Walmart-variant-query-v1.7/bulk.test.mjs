import test from 'node:test';
import assert from 'node:assert/strict';
import { parseVariantKey, analyzeBulk, bulkCsv, fetchVariantBatch } from './bulk.mjs';

const page = product => `<!doctype html><html><head><script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { initialData: { data: { product } } } } })}</script></head><body></body></html>`;

test('parseVariantKey 通用解析：维度不一定是 actual_color，也可能没有尺码', () => {
  assert.deepEqual(parseVariantKey('actual_color-darkgreenstripe-clothing_size-m'), { dim: 'actual_color', value: 'darkgreenstripe', sizeKey: 'm', size: 'M' });
  assert.deepEqual(parseVariantKey('pattern-darkcolor-clothing_size-3xl'), { dim: 'pattern', value: 'darkcolor', sizeKey: '3xl', size: '3XL' });
  assert.deepEqual(parseVariantKey('actual_color-pinkstars'), { dim: 'actual_color', value: 'pinkstars', sizeKey: '', size: '?' });
});

test('fetchVariantBatch 解析 orderLimit；缺货 SKU 的 maxOrderQuantity 缺失记 null', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async url => {
    const id = String(url).split('/ip/')[1];
    if (id === 'oos') {
      return new Response(page({
        usItemId: '222', id: 'oos', orderLimit: 0, orderMinLimit: 1,
        availabilityStatus: 'OUT_OF_STOCK', salesUnit: 'EACH',
        priceInfo: { currentPrice: { price: 13.79 }, wasPrice: { price: 18.99 }, isPriceReduced: true },
        fulfillmentOptions: [{ type: 'SHIPPING' }],
      }));
    }
    return new Response(page({
      usItemId: '111', id: 'live', orderLimit: '42', orderMinLimit: 1,
      availabilityStatus: 'IN_STOCK', salesUnit: 'EACH', name: 'Test item', brand: 'Brand',
      sellerDisplayName: 'Seller ', priceInfo: { currentPrice: { price: 13.79 }, wasPrice: { price: 18.99 }, isPriceReduced: true },
      fulfillmentOptions: [{ type: 'SHIPPING', maxOrderQuantity: '42' }],
    }));
  };
  try {
    const rows = await fetchVariantBatch({
      origin: 'https://www.walmart.com',
      items: [
        { key: 'actual_color-black-clothing_size-l', productId: 'live' },
        { key: 'actual_color-black-clothing_size-xl', productId: 'oos' },
      ],
      conc: 2, gapMs: 0, timeoutMs: 5000,
    });
    assert.equal(rows.length, 2);
    const [live, oos] = rows;
    assert.equal(live.ok, true);
    assert.equal(live.orderLimit, 42);
    assert.equal(live.maxOrderQuantity, 42);
    assert.equal(live.seller, 'Seller');
    assert.equal(live.price, 13.79);
    assert.equal(live.wasPrice, 18.99);
    assert.equal(live.isPriceReduced, true);
    assert.equal(oos.ok, true);
    assert.equal(oos.orderLimit, 0);
    assert.equal(oos.maxOrderQuantity, null);
    assert.equal(oos.availabilityStatus, 'OUT_OF_STOCK');
  } finally {
    globalThis.fetch = real;
  }
});

test('fetchVariantBatch 对 403 与非商品页给出可诊断错误', async () => {
  const real = globalThis.fetch;
  globalThis.fetch = async url => {
    const id = String(url).split('/ip/')[1];
    if (id === 'blocked') return new Response('', { status: 403 });
    return new Response('<html><body>Robot or human?</body></html>', { status: 200 });
  };
  try {
    const rows = await fetchVariantBatch({
      origin: 'https://www.walmart.com',
      items: [{ key: 'k1', productId: 'blocked' }, { key: 'k2', productId: 'verify' }],
      conc: 2, gapMs: 0, timeoutMs: 5000,
    });
    assert.equal(rows[0].ok, false);
    assert.equal(rows[0].error, 'blocked');
    assert.equal(rows[1].ok, false);
    assert.equal(rows[1].error, 'empty-page');
  } finally {
    globalThis.fetch = real;
  }
});

test('analyzeBulk 汇总、矩阵与失败归因', () => {
  const state = {
    itemId: '123',
    queue: [
      { key: 'actual_color-black-clothing_size-l' },
      { key: 'actual_color-black-clothing_size-xl' },
      { key: 'actual_color-red-clothing_size-l' },
      { key: 'actual_color-red-clothing_size-xl' },
    ],
    labels: { 'actual_color-black': 'Black', 'actual_color-red': 'Red', 'clothing_size-l': 'L', 'clothing_size-xl': 'XL' },
    rows: [
      { ok: true, key: 'actual_color-black-clothing_size-l', orderLimit: 12, maxOrderQuantity: 12, availabilityStatus: 'IN_STOCK', price: 13.79, wasPrice: 18.99, isPriceReduced: true, itemId: '1', productId: 'p1', seller: 'S' },
      { ok: true, key: 'actual_color-black-clothing_size-xl', orderLimit: 3, maxOrderQuantity: 3, availabilityStatus: 'IN_STOCK', price: 13.79, wasPrice: null, isPriceReduced: false, itemId: '2', productId: 'p2', seller: 'S' },
      { ok: true, key: 'actual_color-red-clothing_size-l', orderLimit: 0, maxOrderQuantity: null, availabilityStatus: 'OUT_OF_STOCK', price: 14.99, wasPrice: 18.99, isPriceReduced: true, itemId: '3', productId: 'p3', seller: 'S' },
    ],
    failures: [{ key: 'actual_color-red-clothing_size-xl', productId: 'p4', error: 'timeout' }],
  };
  const a = analyzeBulk(state);
  assert.equal(a.dim, 'actual_color');
  assert.deepEqual(a.sizes, ['L', 'XL']);
  assert.equal(a.total.sku, 3);
  assert.equal(a.total.sum, 15);
  assert.equal(a.total.avg, 5);
  assert.equal(a.status.inStock, 2);
  assert.equal(a.status.outOfStock, 1);
  assert.deepEqual(a.low.map(row => row.orderLimit), [3]);
  assert.equal(a.cells.black.L.orderLimit, 12);
  assert.equal(a.cells.red.XL, undefined);
  assert.equal(a.values.find(value => value.value === 'red').total, 0);
  assert.equal(a.prices.length, 2);
  assert.equal(a.noWasCount, 1);
  assert.deepEqual(a.failureCount, [{ reason: 'timeout', count: 1 }]);
  assert.equal(a.expected, 4);
});

test('bulkCsv 带 BOM、14 列、卖家含逗号会加引号', () => {
  const state = {
    queue: [{ key: 'actual_color-black-clothing_size-l' }],
    labels: {},
    rows: [{ ok: true, key: 'actual_color-black-clothing_size-l', orderLimit: 7, maxOrderQuantity: 7, orderMinLimit: 1, availabilityStatus: 'IN_STOCK', price: 9.99, wasPrice: null, isPriceReduced: false, itemId: '1', productId: 'p1', seller: 'Acme, Inc' }],
    failures: [],
  };
  const csv = bulkCsv(state);
  assert.equal(csv.charCodeAt(0), 0xfeff);
  const [header, line] = csv.slice(1).split('\r\n');
  assert.equal(header, 'variant_dim,variant_value,variant_label,size,usItemId,productId,orderLimit,maxOrderQuantity,orderMinLimit,availabilityStatus,price,wasPrice,isPriceReduced,seller');
  assert.equal(line, 'actual_color,black,Black,L,1,p1,7,7,1,IN_STOCK,9.99,,false,"Acme, Inc"');
});
