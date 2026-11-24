import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { Script } from 'node:vm';
import { readCurrentItem, summarizeQuantity } from './item-reader.mjs';

const injected = new Script(`(${readCurrentItem.toString()})()`);
const blackId = '14277256777';
const greenId = '16957755597';
const brownId = '18807165927';
const baseUrl = 'https://www.walmart.com';

function product(itemId, options) {
  return {
    usItemId: Number(itemId),
    variantsMap: {
      black: { usItemId: Number(blackId), variants: ['actual_color-black', 'clothing_size-l'] },
      green: { usItemId: greenId, variants: ['actual_color-green', 'clothing_size-xl'] },
      brown: { usItemId: Number(brownId), variants: ['actual_color-brown', 'clothing_size-l'] },
    },
    variantCriteria: [
      {
        id: 'actual_color',
        variantList: [
          { id: 'actual_color-black', name: '01-Black', usItemId: blackId },
          { id: 'actual_color-green', displayName: '06-Army Green', usItemId: blackId },
          { id: 'actual_color-brown', name: '27-Brown', usItemId: brownId },
        ],
      },
      {
        id: 'clothing_size',
        variantList: [
          { id: 'clothing_size-l', name: 'L' },
          { id: 'clothing_size-xl', name: 'XL' },
        ],
      },
    ],
    fulfillmentOptions: options,
  };
}

function shipping(fields = {}, availabilityStatus = 'IN_STOCK') {
  return { type: 'SHIPPING', availabilityStatus, ...fields };
}

function pageData(product) {
  return JSON.stringify({ props: { pageProps: { initialData: { data: { product } } } } });
}

function read(url, raw) {
  const result = injected.runInNewContext({
    location: new URL(url),
    document: {
      getElementById(id) {
        assert.equal(id, '__NEXT_DATA__');
        return raw === undefined ? null : { textContent: raw };
      },
    },
  });
  return JSON.parse(JSON.stringify(result));
}

test('serialized injection matches the current Black and Army Green Item IDs, labels, and quantities', () => {
  for (const { itemId, color, size, amount, url } of [
    { itemId: blackId, color: '01-Black', size: 'L', amount: 408, url: `${baseUrl}/ip/${blackId}` },
    { itemId: greenId, color: '06-Army Green', size: 'XL', amount: 95, url: `${baseUrl}/ip/MINTREUS%20Lounge/${greenId}/?classType=VARIANT#details` },
  ]) {
    const fields = { orderLimit: amount, maxOrderQuantity: amount, availableQuantity: null };
    const data = product(itemId, [shipping(fields)]);
    if (itemId === greenId) data.usItemId = greenId;
    const item = read(url, pageData(data));
    assert.deepEqual({ itemId: item.itemId, color: item.color, size: item.size, shippingCount: item.shippingCount, availability: item.availability, fields: item.fields }, { itemId, color, size, shippingCount: 1, availability: 'IN_STOCK', fields });
    assert.deepEqual(summarizeQuantity(item), { amount, reason: 'match' });
  }
});

test('supports a productId URL and preserves independent evidence fields', () => {
  const data = product(blackId, [shipping({ orderLimit: 408, availableQuantity: null })]);
  Object.assign(data, { id: '6PQ7Z0ISEFIE', orderLimit: 77, sellerDisplayName: 'MINTREUS', sellerId: 'seller-1', availabilityStatus: 'IN_STOCK', selectedVariantIds: ['actual_color-black', 'clothing_size-l'], primaryOffer: { offerId: 'offer-1' }, priceInfo: { currentPrice: { price: 18.29, currencyUnit: 'USD' } } });
  const item = read(`${baseUrl}/ip/6PQ7Z0ISEFIE`, pageData(data));
  assert.equal(item.itemId, blackId);
  assert.equal(item.productId, '6PQ7Z0ISEFIE');
  assert.equal(item.topOrderLimit, 77);
  assert.equal(item.fields.orderLimit, 408);
  assert.equal(item.seller, 'MINTREUS');
  assert.equal(item.sellerId, 'seller-1');
  assert.equal(item.offerId, 'offer-1');
  assert.equal(item.price, 18.29);
  assert.equal(item.currency, 'USD');
  assert.deepEqual(item.selectedTags, ['actual_color-black', 'clothing_size-l']);
  assert.equal(read(`${baseUrl}/ip/wrongid`, pageData(data)).error, 'item-mismatch');
});

test('rejects selected variant tags inconsistent with the matched item', () => {
  const data = product(blackId, [shipping({ orderLimit: 408 })]);
  data.selectedVariantIds = ['actual_color-green', 'clothing_size-xl'];
  assert.equal(read(`${baseUrl}/ip/${blackId}`, pageData(data)).error, 'variant-mismatch');
});

test('extracts the page variant summaries without attaching the selected quantity to other items', () => {
  const data = product(blackId, [shipping({ orderLimit: 408 })]);
  data.variantsMap.black.availabilityStatus = 'IN_STOCK';
  data.variantsMap.black.priceInfo = { currentPrice: { price: 18.29, currencyUnit: 'USD' } };
  data.variantsMap.green.availabilityStatus = 'OUT_OF_STOCK';
  data.variantsMap.duplicate = { ...data.variantsMap.black };
  data.variantsMap.invalid = { usItemId: 'not-an-item' };
  const result = read(`${baseUrl}/ip/${blackId}`, pageData(data));
  assert.equal(result.variants.length, 3);
  assert.equal(result.duplicateCount, 1);
  assert.equal(result.invalidCount, 1);
  assert.equal(result.variants[0].color, '01-Black');
  assert.equal(result.variants[0].price, 18.29);
  assert.equal(result.variants[1].status, 'OUT_OF_STOCK');
  assert.equal(Object.hasOwn(result.variants[1], 'orderLimit'), false);
});

test('rejects stale payload for a different Item even if the selected Item exists in its variant map', () => {
  const oldPage = product(blackId, [shipping({ orderLimit: 408 })]);
  assert.deepEqual(read(`${baseUrl}/ip/${greenId}`, pageData(oldPage)), { error: 'item-mismatch', itemId: greenId });
});

test('reports missing or malformed page payload without returning a quantity', () => {
  const url = `${baseUrl}/ip/${blackId}`;
  for (const raw of [undefined, '', '{}', pageData({})]) {
    assert.deepEqual(read(url, raw), { error: 'no-page-data', itemId: blackId });
  }
  assert.deepEqual(read(url, '<broken>'), { error: 'invalid-page-data', itemId: blackId });
});

test('stops on a known HTTP 429 before reading page data', () => {
  const result = injected.runInNewContext({
    location: new URL(`${baseUrl}/ip/${blackId}`),
    performance: { getEntriesByType: () => [{ responseStatus: 429 }] },
    document: { getElementById() { assert.fail('Must stop on HTTP 429'); } },
  });
  assert.equal(result.error, 'http-429');
});

test('uses exactly one SHIPPING option, never pickup or delivery', () => {
  const url = `${baseUrl}/ip/${blackId}`;
  const pickup = { type: 'PICKUP', availabilityStatus: 'IN_STOCK', orderLimit: 90 };
  const delivery = { type: 'DELIVERY', availabilityStatus: 'IN_STOCK', orderLimit: 99 };
  const readOptions = options => read(url, pageData(product(blackId, options)));
  const valid = readOptions([pickup, shipping({ maxOrderQuantity: 7 }), delivery]);
  assert.deepEqual(valid.fields, { maxOrderQuantity: 7 });
  assert.deepEqual(summarizeQuantity(valid), { amount: 7, reason: 'max-only' });
  assert.deepEqual(summarizeQuantity(readOptions([pickup, delivery])), { amount: null, reason: 'no-shipping' });
  assert.deepEqual(summarizeQuantity(readOptions([shipping(), shipping()])), { amount: null, reason: 'multiple-shipping' });
});

test('preserves out-of-stock nulls and does not present a conflicting positive limit as purchasable', () => {
  const url = `${baseUrl}/ip/${brownId}`;
  const fields = { orderLimit: null, maxOrderQuantity: null, availableQuantity: null };
  const readFields = fields => read(url, pageData(product(brownId, [shipping(fields, 'OUT_OF_STOCK')])));
  const unavailable = readFields(fields);
  assert.equal(unavailable.availability, 'OUT_OF_STOCK');
  assert.deepEqual(unavailable.fields, fields);
  assert.deepEqual(summarizeQuantity(unavailable), { amount: null, reason: 'not-returned' });
  const inconsistent = readFields({ ...fields, orderLimit: 18, maxOrderQuantity: 18 });
  assert.equal(inconsistent.fields.orderLimit, 18);
  assert.deepEqual(summarizeQuantity(inconsistent), { amount: null, reason: 'out-of-stock-limit' });
});

test('distinguishes missing and null values from numeric zero', () => {
  const url = `${baseUrl}/ip/${blackId}`;
  const readFields = fields => read(url, pageData(product(blackId, [shipping(fields)])));
  const missing = readFields({ availableQuantity: 999 });
  assert.deepEqual(missing.fields, { availableQuantity: 999 });
  assert.deepEqual(summarizeQuantity(missing), { amount: null, reason: 'not-returned' });
  const zero = readFields({ orderLimit: 0, maxOrderQuantity: null });
  assert.deepEqual(zero.fields, { orderLimit: 0, maxOrderQuantity: null });
  assert.deepEqual(summarizeQuantity(zero), { amount: 0, reason: 'order-only' });
});

test('retains both raw values when limits disagree and rejects invalid quantities', () => {
  const url = `${baseUrl}/ip/${blackId}`;
  const readFields = fields => read(url, pageData(product(blackId, [shipping(fields)])));
  const fields = { orderLimit: '15', maxOrderQuantity: 16 };
  const mismatch = readFields(fields);
  assert.deepEqual(mismatch.fields, fields);
  assert.deepEqual(summarizeQuantity(mismatch), { amount: null, reason: 'conflict' });
  assert.deepEqual(summarizeQuantity(readFields({ orderLimit: -1, maxOrderQuantity: '1e3' })), { amount: null, reason: 'not-returned' });
  assert.deepEqual(summarizeQuantity(readFields({ orderLimit: '015', maxOrderQuantity: 15 })), { amount: 15, reason: 'match' });
});

test('refuses non-product or non-US-Walmart pages without reading their DOM', () => {
  const badUrls = [
    'file:///C:/Desktop/sample.html',
    'https://walmart.com.example.com/ip/14277256777',
    'http://www.walmart.com/ip/14277256777',
    'https://www.walmart.ca/ip/14277256777',
    'https://seller.walmart.com/ip/14277256777',
    'https://www.walmart.com/search?q=14277256777',
    'https://www.walmart.com/ip/14277256777/extra/more',
  ];
  for (const url of badUrls) {
    const result = injected.runInNewContext({
      location: new URL(url),
      document: { getElementById() { assert.fail('Unexpected DOM access'); } },
    });
    assert.equal(result.error, 'unsupported-page', url);
  }
});

test('manifest uses user-activated single-tab scripting without persistent site permissions', async () => {
  const manifest = JSON.parse(await readFile(new URL('./manifest.json', import.meta.url), 'utf8'));
  assert.equal(manifest.manifest_version, 3);
  assert.deepEqual([...manifest.permissions].sort(), ['activeTab', 'alarms', 'scripting', 'storage']);
  assert.equal(manifest.background.service_worker, 'scanner.mjs');
  for (const key of ['content_scripts', 'host_permissions', 'optional_host_permissions', 'optional_permissions']) {
    assert.equal(Object.hasOwn(manifest, key), false, key);
  }
});
