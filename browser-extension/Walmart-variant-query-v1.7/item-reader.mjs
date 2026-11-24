// Chrome serializes this function for execution in the active tab's isolated
// world. Keep all browser-side helpers inside it and return only the fields used.
export function readCurrentItem() {
  const navigation = typeof performance === 'object' ? performance.getEntriesByType?.('navigation')?.[0] : null;
  if (navigation?.responseStatus >= 400) return { error: `http-${navigation.responseStatus}` };
  const urlId = location.pathname.match(/^\/ip\/(?:[^/]+\/)?([a-z0-9]+)\/?$/i)?.[1];
  if (location.protocol !== 'https:' || !/^(?:www\.)?walmart\.com$/i.test(location.hostname) || !urlId) {
    return { error: 'unsupported-page' };
  }

  const raw = document.getElementById('__NEXT_DATA__')?.textContent;
  if (!raw) return { error: 'no-page-data', itemId: urlId };
  let product;
  try {
    product = JSON.parse(raw)?.props?.pageProps?.initialData?.data?.product;
  } catch {
    return { error: 'invalid-page-data', itemId: urlId };
  }
  if (!product?.usItemId) return { error: 'no-page-data', itemId: urlId };
  const itemId = String(product.usItemId);
  if (/^\d+$/.test(urlId) ? itemId !== urlId : String(product.id ?? '').toUpperCase() !== urlId.toUpperCase()) return { error: 'item-mismatch', itemId: urlId };

  const text = (value, limit = 100) => typeof value === 'string' ? value.trim().slice(0, limit) : '';
  const variants = Object.values(product.variantsMap ?? {}).filter(v => String(v?.usItemId) === itemId);
  if (variants.length === 1 && Array.isArray(variants[0].variants) && Array.isArray(product.selectedVariantIds) && product.selectedVariantIds.length) {
    const actual = variants[0].variants.filter(v => typeof v === 'string');
    const selected = product.selectedVariantIds.filter(v => typeof v === 'string');
    if (selected.some(tag => !actual.includes(tag))) return { error: 'variant-mismatch', itemId };
  }
  const criteria = Array.isArray(product.variantCriteria) ? product.variantCriteria : [];
  function label(criterionId, variant = variants.length === 1 ? variants[0] : null) {
    if (!Array.isArray(variant?.variants)) return '';
    const tags = variant.variants.filter(v => typeof v === 'string' && v.startsWith(criterionId + '-'));
    if (tags.length !== 1) return '';
    const list = criteria.find(c => c?.id === criterionId)?.variantList;
    if (!Array.isArray(list)) return '';
    const choices = list.filter(v => v?.id === tags[0]);
    if (choices.length !== 1) return '';
    return text(choices[0].name) || text(choices[0].displayName);
  }

  const options = Array.isArray(product.fulfillmentOptions) ? product.fulfillmentOptions : [];
  const shipping = options.filter(option => option?.type === 'SHIPPING');
  function scalar(value) {
    if (value === null || (typeof value === 'number' && Number.isFinite(value))) return value;
    if (typeof value === 'string' && value.length <= 48) return value;
    return undefined;
  }
  const option = shipping.length === 1 ? shipping[0] : null;
  const fields = {};
  const topOrderLimit = scalar(product.orderLimit);
  for (const key of ['orderLimit', 'maxOrderQuantity', 'availableQuantity']) {
    const value = scalar(option?.[key]);
    if (value !== undefined) fields[key] = value;
  }
  const list = [];
  const seen = new Set();
  let duplicateCount = 0;
  let invalidCount = 0;
  for (const variant of Object.values(product.variantsMap ?? {})) {
    const id = String(variant?.usItemId ?? '');
    if (!/^\d+$/.test(id)) { invalidCount++; continue; }
    if (seen.has(id)) { duplicateCount++; continue; }
    seen.add(id);
    list.push({
      itemId: id,
      productId: text(variant.id, 80),
      color: label('actual_color', variant),
      size: label('clothing_size', variant),
      status: text(variant.availabilityStatus, 48),
      price: scalar(variant.priceInfo?.currentPrice?.price),
      currency: text(variant.priceInfo?.currentPrice?.currencyUnit, 16),
    });
  }
  return {
    itemId,
    productId: text(product.id, 80),
    seller: text(product.sellerDisplayName) || text(product.sellerName),
    sellerId: text(product.sellerId, 100),
    offerId: text(product.primaryOffer?.offerId, 100),
    pageStatus: text(product.availabilityStatus, 48),
    topOrderLimit,
    price: scalar(product.priceInfo?.currentPrice?.price),
    currency: text(product.priceInfo?.currentPrice?.currencyUnit, 16),
    selectedTags: Array.isArray(product.selectedVariantIds) ? product.selectedVariantIds.filter(v => typeof v === 'string').slice(0, 8) : [],
    color: label('actual_color'),
    size: label('clothing_size'),
    shippingCount: shipping.length,
    availability: text(option?.availabilityStatus, 48),
    fields,
    variants: list,
    duplicateCount,
    invalidCount,
  };
}

function quantity(value) {
  const number = typeof value === 'string' && /^\d+$/.test(value.trim()) ? Number(value.trim()) : value;
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

export function summarizeQuantity(item) {
  if (!item.shippingCount) return { amount: null, reason: 'no-shipping' };
  if (item.shippingCount > 1) return { amount: null, reason: 'multiple-shipping' };
  const limit = quantity(item.fields.orderLimit);
  const max = quantity(item.fields.maxOrderQuantity);
  if (limit !== null && max !== null && limit !== max) return { amount: null, reason: 'conflict' };
  const amount = limit ?? max;
  if (amount === null) return { amount: null, reason: 'not-returned' };
  if (item.availability === 'OUT_OF_STOCK' && amount > 0) return { amount: null, reason: 'out-of-stock-limit' };
  return { amount, reason: limit === null ? 'max-only' : max === null ? 'order-only' : 'match' };
}
