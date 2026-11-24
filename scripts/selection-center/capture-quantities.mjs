import { appendFile, mkdir, readFile, rename, truncate, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { createHash } from "node:crypto";
import { readPdpBatch, requireListingScope, restrictionRetryTime } from "./capture-products.mjs";

const businessDay = new Intl.DateTimeFormat("en-CA", {
  year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Shanghai",
});

async function json(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function checkpoint(path, value) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(value), "utf8");
  await rename(temporary, path);
}

function positiveInteger(value, fallback, minimum, maximum) {
  const number = value ?? fallback;
  if (!Number.isInteger(number) || number < minimum || number > maximum) {
    throw new Error(`Expected an integer from ${minimum} to ${maximum}`);
  }
  return number;
}

function createTargets(products) {
  const knownItems = new Map();
  const productOnly = new Map();
  const knownProductIds = new Set();
  for (const product of Object.values(products)) {
    if (product.status !== "ok") continue;
    for (const variant of product.variants) {
      const itemId = /^\d+$/.test(variant.itemId ?? "") ? String(variant.itemId) : null;
      const productId = /^[a-z0-9]+$/i.test(variant.productId ?? "") ? variant.productId.toUpperCase() : null;
      if (!itemId && !productId) continue;
      const targetKey = itemId ? `item:${itemId}` : `product:${productId}`;
      const selected = variant.quantitySource === "selected-pdp" && Object.hasOwn(variant, "verifiedQuantity")
        ? variant : null;
      const target = { targetKey, itemId, productId, selected };
      if (itemId) {
        if (!knownItems.has(targetKey) || selected) knownItems.set(targetKey, target);
        if (productId) knownProductIds.add(productId);
      } else if (!productOnly.has(targetKey)) {
        productOnly.set(targetKey, target);
      }
    }
  }
  return [...knownItems.values(), ...[...productOnly.values()].filter(target => !knownProductIds.has(target.productId))];
}

function seededObservation(target, date) {
  const selected = target.selected;
  if (!selected || !selected.observedAt || businessDay.format(new Date(selected.observedAt)) !== date) return null;
  const { itemId, productId, targetKey } = target;
  const {
    availabilityStatus, shippingStatus, price, orderLimit, maxOrderQuantity,
    verifiedQuantity, quantityIssue, sellerId, offerId, fulfillmentType, observedAt,
  } = selected;
  return {
    itemId, productId, targetKey, availabilityStatus, shippingStatus, price,
    orderLimit, maxOrderQuantity, verifiedQuantity, quantityIssue, sellerId, offerId,
    fulfillmentType, observedAt, status: "ok", source: "selected-pdp", attempts: 0,
  };
}

async function readJournal(path, captureId) {
  let buffer;
  try {
    buffer = await readFile(path);
  } catch (error) {
    if (error.code === "ENOENT") return { results: {}, restriction: null };
    throw error;
  }
  const results = {};
  let restriction = null;
  let end = buffer.lastIndexOf(10) + 1;
  for (const line of buffer.subarray(0, end).toString("utf8").split("\n")) {
    if (!line) continue;
    const batch = JSON.parse(line);
    if (batch.captureId !== captureId) {
      throw new Error("Existing quantities belong to a different listing snapshot. Use a separate output directory.");
    }
    for (const result of batch.results) results[result.targetKey] = result;
    restriction = batch.restriction ?? null;
  }
  if (end < buffer.length) await truncate(path, end);
  return { results, restriction };
}

function candidateIds(listing) {
  return new Set(listing.pages.flatMap(page => page.items)
    .map(item => String(item.itemId ?? "")).filter(itemId => /^\d+$/.test(itemId)));
}

// Run one worker for this output directory. Each finished batch is written before the next GET.
// Listing and product captures establish the SKU population and ZIP for this comparison day.
export async function captureQuantities({ page, listingPath, productsPath, outputDir = dirname(listingPath), ...config }) {
  const sourceProductsPath = productsPath ?? join(dirname(listingPath), "products.json");
  const listing = await json(listingPath);
  await mkdir(outputDir, { recursive: true });
  const exportPath = join(outputDir, "quantities.json");
  const journalPath = join(outputDir, "quantities.ndjson");
  const statePath = join(outputDir, "quantities.meta.json");
  const snapshotDate = businessDay.format(new Date(listing.observedAt));
  const expectedZip = String(listing.zip ?? "");
  const listingIdentity = [listing.sourceUrl, expectedZip, listing.observedAt].join("|");
  const captureId = createHash("sha256").update(listingIdentity).digest("hex");
  const catalog = candidateIds(listing);
  const listedRestriction = listing.errors?.find(error => error.blocked);
  if (listedRestriction) {
    const result = {
      output: exportPath, captureId, snapshotDate, listingComplete: false, stopReason: listedRestriction.error,
      catalogCandidates: catalog.size, catalogCandidatePdpsComplete: false, catalogMembershipComplete: false,
      skuReadsComplete: false, skuQuantitiesComplete: false,
    };
    await checkpoint(statePath, result);
    return result;
  }
  const productState = await json(join(dirname(sourceProductsPath), "products.meta.json"));
  if (productState.listingIdentity !== listingIdentity) throw new Error("PDP results do not belong to this listing snapshot");
  const allProducts = await json(sourceProductsPath);
  const products = Object.fromEntries([...catalog].filter(id => allProducts[id]).map(id => [id, allProducts[id]]));
  const verifiedProducts = Object.values(products).filter(product => product.status === "ok");
  const withMembership = verifiedProducts.filter(product => ["pdp-variants", "pdp-regular"].includes(product.membershipSource) &&
    product.expectedSkuCount === product.variants.length);
  const targets = createTargets(products);
  const { results, restriction: previousRestriction } = await readJournal(journalPath, captureId);
  let restriction = previousRestriction;
  let stopReason = productState.blockedAt ? productState.blockReason ?? "PDP capture restricted" : null;

  const batchSize = positiveInteger(config.concurrency, 1, 1, 3);
  const waitMs = positiveInteger(config.waitMs, 1250, 750, 10000);
  const timeoutMs = positiveInteger(config.timeoutMs, 14000, 3000, 25000);
  const limit = positiveInteger(config.limit, 100, 1, 3000);
  const maxAttempts = positiveInteger(config.maxAttempts, 2, 1, 4);
  const finalStates = new Set(["ok", "not-found", "id-mismatch"]);
  const inWindow = () => businessDay.format(new Date()) === snapshotDate;

  if (!stopReason && restriction && (!config.resumeAfterAccessRestored ||
      (restriction.retryNotBefore && Date.now() < Date.parse(restriction.retryNotBefore)))) {
    stopReason = restriction.reason;
  }
  if (!stopReason && !inWindow()) stopReason = "The listing snapshot day has ended";

  for (const target of targets) {
    if (results[target.targetKey]) continue;
    const seed = seededObservation(target, snapshotDate);
    if (seed) results[target.targetKey] = seed;
  }
  const isPending = ({ targetKey }) => {
    const result = results[targetKey];
    return !finalStates.has(result?.status) && (result?.attempts ?? 0) < maxAttempts;
  };
  const pending = targets.filter(isPending).slice(0, limit);

  if (!stopReason && pending.length) {
    await requireListingScope(page, expectedZip);
    for (let offset = 0; offset < pending.length; offset += batchSize) {
      if (!inWindow() || Date.now() >= (config.deadlineEpochMs ?? Infinity)) {
        stopReason = !inWindow() ? "The listing snapshot day has ended" : "Capture time budget ended";
        break;
      }
      const entries = pending.slice(offset, offset + batchSize).map(({ targetKey, itemId, productId }) => ({ targetKey, itemId, productId }));
      const observations = await page.evaluate(readPdpBatch, { entries, timeoutMs, expectedZip, quantityOnly: true });
      const rows = observations.map(observation => {
        const inDay = businessDay.format(new Date(observation.observedAt)) === snapshotDate;
        const accepted = inDay || observation.status !== "ok" ? observation : {
          targetKey: observation.targetKey, itemId: observation.itemId, productId: observation.productId,
          observedAt: observation.observedAt, status: "window-expired", issue: "The listing snapshot day has ended",
        };
        return { ...accepted, source: "sku-pdp", attempts: (results[observation.targetKey]?.attempts ?? 0) + 1 };
      });
      const blocked = rows.find(row => row.status === "blocked" || row.status === "location-mismatch");
      if (blocked) {
        stopReason = blocked.issue;
        restriction = { reason: blocked.issue, observedAt: blocked.observedAt, retryNotBefore: restrictionRetryTime(blocked.retryAfter, blocked.observedAt, blocked.issue) };
      } else {
        restriction = null;
      }
      await appendFile(journalPath, JSON.stringify({ captureId, results: rows, restriction }) + "\n", "utf8");
      for (const row of rows) results[row.targetKey] = row;
      if (restriction) break;
      if (rows.some(row => row.status === "window-expired")) {
        stopReason = "The listing snapshot day has ended";
        break;
      }
      if (offset + batchSize < pending.length) await new Promise(resolve => setTimeout(resolve, waitMs));
    }
  }

  const observations = targets.map(({ targetKey }) => results[targetKey]).filter(Boolean);
  const verifiedSkuReads = observations.filter(row => row.status === "ok").length;
  const verifiedSkuQuantities = observations.filter(row => row.status === "ok" && Number.isInteger(row.verifiedQuantity)).length;
  const readErrorsByStatus = {};
  for (const row of observations) {
    if (row.status !== "ok") readErrorsByStatus[row.status] = (readErrorsByStatus[row.status] ?? 0) + 1;
  }
  const summary = {
    output: exportPath,
    captureId,
    snapshotDate,
    listingComplete: listing.complete === true,
    catalogCandidates: catalog.size,
    catalogCandidatePdpsVerified: verifiedProducts.length,
    catalogCandidatePdpFailures: Object.keys(products).length - verifiedProducts.length,
    catalogCandidatePdpsUnvisited: catalog.size - Object.keys(products).length,
    catalogCandidatePdpsComplete: catalog.size > 0 && verifiedProducts.length === catalog.size,
    catalogMembershipComplete: catalog.size > 0 && withMembership.length === catalog.size,
    catalogCandidateMembershipVerified: withMembership.length,
    targetSkus: targets.length,
    observedSkus: observations.length,
    pendingSkus: targets.filter(isPending).length,
    unvisitedSkus: targets.length - observations.length,
    verifiedSkuReads,
    failedSkuReads: observations.length - verifiedSkuReads,
    readErrorsByStatus,
    verifiedSkuQuantities,
    skuReadsComplete: targets.length > 0 && verifiedSkuReads === targets.length,
    skuQuantitiesComplete: targets.length > 0 && verifiedSkuQuantities === targets.length,
    stopReason,
    restriction,
  };
  await checkpoint(exportPath, Object.fromEntries(observations.map(row => [row.targetKey, row])));
  await checkpoint(statePath, summary);
  return summary;
}
