export function filterVariants(variants, query = '', status = 'ALL') {
  const terms = query.trim().toLocaleLowerCase();
  return variants.filter(row => (status === 'ALL' || row.status === status) &&
    (!terms || [row.color, row.size, row.itemId, row.productId].some(value => String(value ?? '').toLocaleLowerCase().includes(terms))));
}

export function parseHistoricalCsv(input) {
  if (input.length > 5_000_000) throw new Error('文件超过 5 MB。');
  const rows = []; let row = []; let value = ''; let quoted = false;
  input = input.replace(/^\uFEFF/, '');
  for (let i = 0; i < input.length; i++) {
    const char = input[i];
    if (quoted) {
      if (char === '"' && input[i + 1] === '"') { value += '"'; i++; }
      else if (char === '"') quoted = false;
      else value += char;
    } else if (char === '"' && value === '') quoted = true;
    else if (char === ',') { row.push(value); value = ''; }
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && input[i + 1] === '\n') i++;
      row.push(value); if (row.some(cell => cell !== '')) rows.push(row); row = []; value = '';
    } else value += char;
  }
  if (quoted) throw new Error('CSV 引号未闭合。');
  row.push(value); if (row.some(cell => cell !== '')) rows.push(row);
  const header = (rows.shift() || []).map(v => v.trim().toLowerCase());
  const idIndex = header.indexOf('usitemid');
  const limitIndex = header.indexOf('orderlimit');
  const statusIndex = header.indexOf('availabilitystatus');
  if (idIndex < 0 || limitIndex < 0) throw new Error('需要 usItemId 和 orderLimit 列。');
  const result = new Map(); const duplicates = new Set();
  for (const fields of rows) {
    const id = (fields[idIndex] || '').trim(); const limit = (fields[limitIndex] || '').trim();
    if (!/^\d+$/.test(id) || !/^\d+$/.test(limit) || !Number.isSafeInteger(Number(limit))) continue;
    if (result.has(id)) { duplicates.add(id); result.delete(id); continue; }
    if (!duplicates.has(id)) result.set(id, { limit: Number(limit), status: statusIndex < 0 ? '' : (fields[statusIndex] || '').trim().slice(0, 48) });
  }
  return { result, duplicateCount: duplicates.size };
}

export function variantTsv(variants, currentItemId, amount, historical = new Map()) {
  const safe = value => {
    const result = String(value ?? '').replace(/[\t\r\n]+/g, ' ').trim();
    return /^[=+\-@]/.test(result) ? "'" + result : result;
  };
  const rows = variants.map(row => {
    const old = historical.get(row.itemId);
    return [row.color, row.size, row.itemId, row.productId, row.status || '未返回', row.price ?? '未返回', row.currency || '未返回', row.itemId === currentItemId && amount !== null ? amount : '未取得', old?.limit ?? '未导入', old?.status || '未导入'];
  });
  return [['颜色', '尺码', 'Item ID', 'Product ID', '变体状态', '价格', '币种', '当前页单笔上限', '历史orderLimit', '历史状态'], ...rows].map(row => row.map(safe).join('\t')).join('\n');
}
