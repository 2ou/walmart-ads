// Read only the listing entry's PDP. Other variants receive metadata but no quantity.
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { createHash } from "node:crypto";

const captureDay = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" });

export function observedDay(value = Date.now()) {
  return captureDay.format(new Date(value));
}

export function restrictionRetryTime(value, observedAt, issue) {
  const received = Date.parse(observedAt);
  const header = value && (/^\d+$/.test(value) ? received + Number(value) * 1000 : Date.parse(value));
  const timestamp = Number.isFinite(header) ? header : issue === "HTTP 429" ? received + 120000 : NaN;
  const retry = new Date(timestamp);
  return Number.isFinite(retry.getTime()) ? retry.toISOString() : null;
}

async function readJson(path, fallback) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT" && fallback !== undefined) return fallback;
    throw error;
  }
}

async function saveJson(path, value) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(value), "utf8");
  await rename(temporary, path);
}

function integer(value, fallback, min, max) {
  const number = Number(value ?? fallback);
  if (!Number.isInteger(number) || number < min || number > max) {
    throw new Error(`Expected an integer from ${min} to ${max}: ${value}`);
  }
  return number;
}

// Serialized into the Walmart page. Keep all browser-side dependencies inside this function.
export async function readPdpBatch({ entries, timeoutMs, expectedZip, quantityOnly = false }) {
  if (location.origin !== "https://www.walmart.com") throw new Error("Expected a Walmart page context");

  const text = value => typeof value === "string" ? value.trim() || null : null;
  const identifier = value => typeof value === "number" && Number.isSafeInteger(value) ? String(value) : text(value);
  const numericItem = value => /^\d+$/.test(identifier(value) ?? "") ? identifier(value) : null;
  const productId = value => /^[a-z0-9]+$/i.test(text(value) ?? "") ? text(value).toUpperCase() : null;
  const number = value => {
    if (typeof value !== "number" && (typeof value !== "string" || value.trim() === "")) return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  };
  const quantity = value => Number.isSafeInteger(number(value)) ? number(value) : null;
  const price = row => number(row?.priceInfo?.currentPrice?.price);
  const availability = row => text(row?.availabilityStatus);
  const objects = value => value && typeof value === "object" ? Object.entries(value) : [];

  function nextData(html) {
    const match = /<script\b[^>]*\bid\s*=\s*["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script\s*>/i.exec(html);
    return match ? JSON.parse(match[1])?.props?.pageProps?.initialData : null;
  }

  function accessChallenge(url, html) {
    if (/\/(?:blocked|captcha|challenge|px-captcha)(?:[/?#]|$)/i.test(url)) return true;
    const title = /<title\b[^>]*>([^<]*)<\/title\s*>/i.exec(html)?.[1] ?? "";
    return /robot or human|access denied|security check|verification required/i.test(title)
      || /verify (?:that )?you are (?:a )?human|press (?:and|&amp;|&) hold to confirm/i.test(html.slice(0, 15000));
  }

  function variantLabels(product) {
    const labels = new Map();
    for (const criterion of Array.isArray(product.variantCriteria) ? product.variantCriteria : []) {
      const choices = criterion?.variantList ?? criterion?.values;
      for (const choice of Array.isArray(choices) ? choices : []) {
        const label = text(choice?.name) ?? text(choice?.displayName) ?? text(choice?.label);
        if (choice?.id && label) labels.set(String(choice.id).toLowerCase(), label);
      }
    }
    return labels;
  }

  function variantDimensions(key, row, labels) {
    const dimensions = [
      ...(Array.isArray(row?.variants) ? row.variants.filter(value => typeof value === "string") : []),
      key,
    ].filter(Boolean);
    function dimension(names) {
      for (const raw of dimensions) {
        for (const name of names) {
          const prefix = `${name}-`;
          const start = raw.toLowerCase().lastIndexOf(prefix);
          if (start < 0) continue;
          const value = raw.slice(start + prefix.length).split(/-(?:actual_color|color|pattern|clothing_size|shoe_size|size|size_type|count|pack_size)-/i)[0];
          if (value) return labels.get(`${name}-${value}`.toLowerCase()) ?? value;
        }
      }
      return null;
    }
    return {
      color: dimension(["actual_color", "color", "pattern"]),
      size: dimension(["clothing_size", "shoe_size", "size"]),
    };
  }

  function shippingObservation(product) {
    const options = Array.isArray(product.fulfillmentOptions)
      ? product.fulfillmentOptions.filter(option => option?.type === "SHIPPING") : [];
    const maxima = new Set(options.map(option => quantity(option.maxOrderQuantity)).filter(value => value !== null));
    const statuses = new Set(options.map(availability).filter(Boolean));
    const shippingOfferIds = new Set(options.map(option => identifier(option.offerId)).filter(Boolean));
    const orderLimit = quantity(product.orderLimit);
    const maxOrderQuantity = maxima.size === 1 ? [...maxima][0] : null;
    const shippingStatus = statuses.size === 1 ? [...statuses][0] : null;
    const productOfferId = identifier(product.offerId);
    const shippingOfferId = shippingOfferIds.size === 1 ? [...shippingOfferIds][0] : null;
    let quantityIssue = null;
    if (!options.length) quantityIssue = "no-shipping-option";
    else if (maxima.size > 1 || statuses.size > 1 || shippingOfferIds.size > 1) quantityIssue = "conflicting-shipping-options";
    else if (productOfferId && shippingOfferId && shippingOfferId !== productOfferId) quantityIssue = "shipping-offer-mismatch";
    else if (orderLimit !== null && maxOrderQuantity !== null && orderLimit !== maxOrderQuantity) quantityIssue = "limit-mismatch";
    else if (orderLimit === null && maxOrderQuantity === null) quantityIssue = "shipping-quantity-missing";
    return {
      price: price(product),
      availabilityStatus: availability(product),
      shippingStatus,
      orderLimit,
      maxOrderQuantity,
      verifiedQuantity: quantityIssue ? null : maxOrderQuantity ?? orderLimit,
      quantityIssue,
      sellerId: identifier(product.sellerId),
      offerId: productOfferId ?? shippingOfferId,
      fulfillmentType: text(product.fulfillmentType),
    };
  }

  function normalize(product, item, observedAt) {
    const selectedId = productId(product.id);
    const labels = variantLabels(product);
    const metadata = new Map(objects(product.variantsMap)
      .map(([id, row]) => [productId(row?.id) ?? productId(id), row]));
    const members = new Map();
    for (const [key, rawId] of objects(product.variantProductIdMap)) {
      const id = productId(rawId);
      if (id && !members.has(id)) members.set(id, key);
    }
    for (const [id, row] of metadata) {
      if (id && !members.has(id)) members.set(id, Array.isArray(row?.variants) ? row.variants.join("-") : null);
    }

    const regularSingleSku = product.classType === "REGULAR" &&
      Array.isArray(product.variantCriteria) && product.variantCriteria.length === 0 &&
      Array.isArray(product.selectedVariantIds) && product.selectedVariantIds.length === 0 &&
      (!numericItem(product.primaryUsItemId) || numericItem(product.primaryUsItemId) === item.itemId);
    const membershipSource = members.size ? "pdp-variants" : regularSingleSku ? "pdp-regular" : "entry-only";
    const entries = [...members];
    if (!entries.some(([id]) => id === selectedId)) entries.push([selectedId, null]);
    const offer = shippingObservation(product);
    const variants = entries.map(([id, key]) => {
      const selected = id === selectedId;
      const row = metadata.get(id);
      return {
        itemId: selected ? item.itemId : numericItem(row?.usItemId),
        productId: id,
        key,
        ...variantDimensions(key, row, labels),
        price: (selected ? price(product) : null) ?? price(row),
        availabilityStatus: selected ? availability(product) : availability(row),
        shippingStatus: selected ? offer.shippingStatus : null,
        orderLimit: selected ? offer.orderLimit : null,
        maxOrderQuantity: selected ? offer.maxOrderQuantity : null,
        verifiedQuantity: selected ? offer.verifiedQuantity : null,
        quantityIssue: selected ? offer.quantityIssue : null,
        quantitySource: selected ? "selected-pdp" : null,
        sellerId: selected ? offer.sellerId : null,
        offerId: selected ? offer.offerId : null,
        fulfillmentType: selected ? offer.fulfillmentType : null,
        observedAt,
      };
    });

    const primaryUsItemId = numericItem(product.primaryUsItemId);
    const parentItemId = numericItem(product.parentItemId);
    return {
      itemId: item.itemId,
      productId: selectedId,
      listingProductId: productId(item.productId),
      primaryUsItemId,
      parentItemId,
      primaryProductId: productId(product.primaryProductId),
      abstractProductId: productId(product.abstractProductId),
      classType: text(product.classType),
      title: text(product.name) ?? item.title ?? null,
      brand: text(product.brand) ?? item.brand ?? null,
      productType: item.productType ?? text(product.productType?.name) ?? text(product.productType),
      image: item.image ?? text(product.imageInfo?.thumbnailUrl),
      price: price(product),
      expectedSkuCount: membershipSource === "entry-only" ? null : variants.length,
      membershipSource,
      variants,
      observedAt,
      status: "ok",
    };
  }

  async function fetchOne(item) {
    const base = { itemId: item.itemId, targetKey: item.targetKey, requestedProductId: item.productId, observedAt: new Date().toISOString() };
    const requestId = numericItem(item.itemId) ?? productId(item.productId);
    if (!requestId) return { ...base, status: "error", issue: "Missing Walmart item or product ID" };
    try {
      const response = await fetch(`/ip/${encodeURIComponent(requestId)}`, {
        credentials: "same-origin",
        headers: { accept: "text/html,application/xhtml+xml" },
        signal: AbortSignal.timeout(timeoutMs),
      });
      if ([401, 403, 412, 418, 429].includes(response.status)) {
        return { ...base, status: "blocked", issue: `HTTP ${response.status}`, retryAfter: response.headers.get("retry-after") };
      }
      if (!response.ok) {
        return { ...base, status: response.status === 404 ? "not-found" : "error", issue: `HTTP ${response.status}` };
      }
      const html = await response.text();
      let data;
      try {
        data = nextData(html);
      } catch {
        data = null;
      }
      const product = data?.data?.product;
      if (!product) {
        const blocked = accessChallenge(response.url, html);
        return { ...base, status: blocked ? "blocked" : "error", issue: blocked ? "Walmart access challenge" : "No PDP product data" };
      }
      const returnedItemId = numericItem(product.usItemId);
      const returnedProductId = productId(product.id);
      const expectedProductId = quantityOnly ? productId(item.productId) : null;
      if (!returnedItemId || (item.itemId && returnedItemId !== item.itemId) ||
          (expectedProductId && returnedProductId !== expectedProductId)) {
        return { ...base, status: "id-mismatch", issue: `Requested ${requestId}, got ${returnedItemId ?? "no Item ID"}/${returnedProductId ?? "no Product ID"}` };
      }
      const reportedZip = data?.pageMetadata?.location?.postalCode ?? data?.data?.pageMetadata?.location?.postalCode;
      if (reportedZip && String(reportedZip) !== expectedZip) {
        return { ...base, status: "location-mismatch", issue: "PDP ZIP does not match the listing snapshot" };
      }
      if (quantityOnly) {
        return {
          ...base,
          itemId: returnedItemId,
          productId: returnedProductId,
          ...shippingObservation(product),
          observedAt: new Date().toISOString(),
          status: "ok",
        };
      }
      return normalize(product, item, new Date().toISOString());
    } catch (error) {
      return { ...base, status: "error", issue: String(error?.message ?? error).slice(0, 180) };
    }
  }

  return Promise.all(entries.map(fetchOne));
}

export async function requireListingScope(page, expectedZip) {
  const scope = await page.evaluate(() => ({
    origin: location.origin,
    zip: window.__NEXT_DATA__?.props?.pageProps?.initialData?.pageMetadata?.location?.postalCode,
  }));
  if (scope.origin !== "https://www.walmart.com" || !expectedZip || String(scope.zip) !== expectedZip) {
    throw new Error("Open the original listing page with the same ZIP in the existing Ego page first");
  }
}

// A primary Item ID is usable only when the PDP explicitly lists it in this variant group.
// Otherwise exact membership provides a conservative grouping key within the snapshot.
function identifyGroup(result) {
  if (result.status !== "ok") return result;
  const { itemId, primaryUsItemId, parentItemId, primaryProductId, abstractProductId, listingProductId, variants } = result;
  const seenItemIds = new Set(variants.map(variant => variant.itemId).filter(Boolean));
  const knownPrimaryItemId = variants.find(variant => variant.productId && variant.productId === primaryProductId)?.itemId;
  const knownParent = [primaryUsItemId, parentItemId, knownPrimaryItemId].find(id => id && seenItemIds.has(id));
  const memberProductIds = [...new Set(variants.map(variant => variant.productId).filter(Boolean))].sort();
  const variantSetKey = memberProductIds.length > 1
    ? `variants:${createHash("sha256").update(memberProductIds.join(",")).digest("hex").slice(0, 24)}`
    : null;
  let groupKey = `item:${itemId}`;
  let groupKeySource = "entry-item";
  if (result.membershipSource === "pdp-regular") {
    return {...result, representativeItemId: itemId, groupKey: `regular:${itemId}`,
      groupKeySource: "pdp-regular-no-variants", variantSetKey};
  }
  if (variants.length > 1 && abstractProductId && abstractProductId === listingProductId) {
    groupKey = `abstract:${abstractProductId}`;
    groupKeySource = "pdp-abstract-confirmed-by-listing";
  } else if (variants.length > 1 && knownParent) {
    groupKey = `primary:${knownParent}`;
    groupKeySource = "pdp-primary-confirmed-in-variants";
  } else if (variantSetKey) {
    groupKey = variantSetKey;
    groupKeySource = "pdp-variant-membership";
  }
  return { ...result, representativeItemId: knownParent ?? itemId, groupKey, groupKeySource, variantSetKey };
}

export async function captureProducts({ page, listingPath, outputDir = dirname(listingPath), ...config }) {
  const listing = await readJson(listingPath);
  await mkdir(outputDir, { recursive: true });
  const productsPath = join(outputDir, "products.json");
  const metaPath = join(outputDir, "products.meta.json");
  const state = await readJson(metaPath, {});
  const products = await readJson(productsPath, {});
  const listingIdentity = [listing.sourceUrl, listing.zip, listing.observedAt].join("|");
  if (Object.keys(products).length && state.listingIdentity !== listingIdentity) {
    throw new Error("Existing products.json belongs to a different listing snapshot. Use a separate output directory.");
  }
  const candidates = new Map();
  for (const item of listing.pages.flatMap(record => record.items)) {
    const itemId = String(item.itemId ?? "");
    if (/^\d+$/.test(itemId) && !candidates.has(itemId)) candidates.set(itemId, { ...item, itemId });
  }

  const limit = integer(config.limit, 100, 1, 3000);
  const concurrency = integer(config.concurrency, 2, 1, 3);
  const timeoutMs = integer(config.timeoutMs, 14000, 3000, 25000);
  const maxAttempts = integer(config.maxAttempts, 2, 1, 4);
  const waitMs = integer(config.waitMs, 800, 500, 10000);
  const terminal = new Set(["ok", "not-found", "id-mismatch"]);
  const isPending = ({ itemId }) => !terminal.has(products[itemId]?.status) && (products[itemId]?.attempts ?? 0) < maxAttempts;
  const date = observedDay(listing.observedAt);
  const listingRestriction = listing.errors?.find(error => error.blocked);
  // A run can stop after writing products.json and before writing its summary.
  // Keep any newly persisted restriction from that gap instead of trying the item again.
  const lastCheckpoint = Date.parse(state.updatedAt) || 0;
  const savedRestriction = Object.values(products).find(row =>
    (row.status === "blocked" || row.status === "location-mismatch") && Date.parse(row.observedAt) > lastCheckpoint);
  const priorBlockedAt = savedRestriction?.observedAt ?? state.blockedAt;
  const priorBlockReason = savedRestriction?.issue ?? state.blockReason;
  const priorRetryNotBefore = savedRestriction
    ? restrictionRetryTime(savedRestriction.retryAfter, priorBlockedAt, priorBlockReason)
    : state.retryNotBefore ?? restrictionRetryTime(null, priorBlockedAt, priorBlockReason);
  const priorRestriction = priorBlockedAt && (!config.resumeAfterAccessRestored || (priorRetryNotBefore && Date.now() < Date.parse(priorRetryNotBefore)));
  let blockedAt = listingRestriction ? listing.finishedAt ?? listing.observedAt : null;
  let blockReason = listingRestriction ? listingRestriction.error ?? "Walmart restricted listing access" : null;
  let retryNotBefore = listingRestriction ? restrictionRetryTime(listingRestriction.retryAfter, listingRestriction.checkedAt ?? blockedAt, blockReason) : null;
  let stopReason = null;
  if (priorRestriction && !blockedAt) {
    blockedAt = priorBlockedAt;
    blockReason = priorBlockReason;
    retryNotBefore = priorRetryNotBefore;
  }

  function getSummary() {
    const rows = [...candidates.keys()].map(id => products[id]).filter(Boolean);
    const valid = rows.filter(row => row.status === "ok");
    return {
      output: productsPath,
      listingIdentity,
      listingComplete: listing.complete === true,
      candidates: candidates.size,
      pdpsVerified: valid.length,
      pdpsWithIssue: rows.length - valid.length,
      pdpMembershipUnresolved: valid.filter(row => row.membershipSource === "entry-only").length,
      variantGroups: new Set(valid.map(row => row.groupKey)).size,
      unvisited: candidates.size - rows.length,
      pending: [...candidates.values()].filter(isPending).length,
      blockedAt,
      blockReason,
      retryNotBefore,
      stopReason,
    };
  }

  const checkpoint = () => saveJson(metaPath, { ...getSummary(), updatedAt: new Date().toISOString() });
  if (!blockedAt && observedDay() !== date) stopReason = "The listing snapshot day has ended";
  await checkpoint();
  if (blockedAt || stopReason) return getSummary();

  const pending = [...candidates.values()].filter(isPending).slice(0, limit);
  if (!pending.length) return getSummary();

  const expectedZip = String(listing.zip ?? "");
  await requireListingScope(page, expectedZip);
  for (let offset = 0; offset < pending.length; offset += concurrency) {
    if (observedDay() !== date || Date.now() >= (config.deadlineEpochMs ?? Infinity)) {
      stopReason = observedDay() !== date ? "The listing snapshot day has ended" : "Capture time budget ended";
      await checkpoint();
      break;
    }
    const entries = pending.slice(offset, offset + concurrency);
    const results = await page.evaluate(readPdpBatch, { entries, timeoutMs, expectedZip });
    for (const result of results) {
      const previous = products[result.itemId];
      const accepted = result.status !== "ok" || observedDay(result.observedAt) === date ? result : {
        itemId: result.itemId, observedAt: result.observedAt, status: "window-expired", issue: "The listing snapshot day has ended",
      };
      products[result.itemId] = { ...identifyGroup(accepted), attempts: (previous?.attempts ?? 0) + 1 };
    }
    const blocked = results.find(result => result.status === "blocked" || result.status === "location-mismatch");
    blockedAt = blocked?.observedAt ?? null;
    blockReason = blocked?.issue ?? null;
    retryNotBefore = blocked ? restrictionRetryTime(blocked.retryAfter, blockedAt, blockReason) : null;
    await saveJson(productsPath, products);
    await checkpoint();
    if (blocked) break;
    if (offset + concurrency < pending.length) await new Promise(resolve => setTimeout(resolve, waitMs));
  }
  return getSummary();
}
