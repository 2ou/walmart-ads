import { test } from 'node:test';
import assert from 'node:assert/strict';
import { filterVariants, variantTsv, parseHistoricalCsv } from './variants.mjs';

const rows = [
  { itemId: '100', productId: 'PROD1', color: 'Black', size: 'L', status: 'IN_STOCK', price: 12.5, currency: 'USD' },
  { itemId: '200', productId: 'PROD2', color: 'Pink', size: '2XL', status: 'OUT_OF_STOCK' },
  { itemId: '300', color: '=unsafe', size: 'XL', status: '' },
];

test('filters by text and stock status', () => {
  assert.deepEqual(filterVariants(rows, 'black', 'IN_STOCK'), [rows[0]]);
  assert.deepEqual(filterVariants(rows, '2xl', 'ALL'), [rows[1]]);
  assert.deepEqual(filterVariants(rows, '200', 'IN_STOCK'), []);
});

test('historical CSV is matched by Item ID, keeps zeros, and removes duplicate IDs', () => {
  const csv = '\uFEFFcolor,usItemId,orderLimit,availabilityStatus\r\n"Red, white",100,0,OUT_OF_STOCK\r\nGreen,200,95,IN_STOCK\r\nBlue,200,12,IN_STOCK\r\n';
  const result = parseHistoricalCsv(csv);
  assert.equal(result.result.size, 1);
  assert.deepEqual(result.result.get('100'), { limit: 0, status: 'OUT_OF_STOCK' });
  assert.equal(result.duplicateCount, 1);
  const lines = variantTsv(rows, '100', 408, result.result).split('\n');
  assert.equal(lines[1].split('\t')[7], '408');
  assert.equal(lines[1].split('\t')[8], '0');
  assert.equal(lines[2].split('\t')[8], '未导入');
});

test('rejects historical CSV without matching headers or with malformed quotes', () => {
  assert.throws(() => parseHistoricalCsv('SKU,qty\nA,5'), /usItemId/);
  assert.throws(() => parseHistoricalCsv('usItemId,orderLimit\n"100,3'), /引号未闭合/);
});

test('copies only the selected item quantity, keeps missing amounts unknown and prevents spreadsheet formulas', () => {
  const lines = variantTsv(rows, '100', 408).split('\n');
  assert.equal(lines[1].split('\t')[7], '408');
  assert.equal(lines[2].split('\t')[7], '未取得');
  assert.match(lines[3], /^'=unsafe\t/);
  assert.equal(variantTsv(rows, '100', null).split('\n')[1].split('\t')[7], '未取得');
});
