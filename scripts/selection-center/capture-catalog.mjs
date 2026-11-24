import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { captureListing } from "./capture-listing.mjs";
import { captureProducts, observedDay, restrictionRetryTime } from "./capture-products.mjs";
import { captureQuantities } from "./capture-quantities.mjs";

async function optionalJson(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

// Import from a fresh Ego script with its currently open Walmart listing Page.
// Call this again with the same outputDir to continue unfinished work on the same day.
// Run at most one invocation per outputDir and leave resumeAfterAccessRestored false
// unless ordinary access has actually returned.
export async function captureCatalog({
  page, sourceUrl, zip, outputDir, timeBudgetMs = 90000,
  resumeAfterAccessRestored = false, listingOptions = {}, productOptions = {}, quantityOptions = {},
}) {
  if (!outputDir) throw new Error("Use a separate outputDir for this catalog snapshot");
  if (!Number.isFinite(timeBudgetMs) || timeBudgetMs < 1000) throw new Error("Invalid time budget");
  const root = resolve(outputDir);
  const listingPath = join(root, "listing.json");
  const deadlineEpochMs = Date.now() + timeBudgetMs;
  let listing = await optionalJson(listingPath);
  sourceUrl ??= listing?.sourceUrl;
  zip ??= listing?.zip;
  if (!sourceUrl || !zip) throw new Error("Walmart source URL and ZIP are required for a new capture");
  const report = { outputDir: root, stage: "listing", status: "pending", listing: null, products: null, quantities: null };

  function listingReport() {
    return listing ? {
      date: observedDay(listing.observedAt), complete: listing.complete === true,
      pages: listing.pages.length, expectedPages: listing.maxPage,
      candidateItems: listing.uniqueItemCount ?? new Set(listing.pages.flatMap(record => record.items.map(item => item.itemId))).size,
      errors: listing.errors ?? [],
    } : null;
  }

  if (listing && (listing.sourceUrl !== sourceUrl || listing.zip !== zip)) {
    throw new Error("Stored listing uses a different URL or ZIP. Use a separate output directory.");
  }
  if (listing && observedDay(listing.observedAt) !== observedDay()) {
    return { ...report, status: "expired", listing: listingReport() };
  }
  if (!listing?.complete) {
    const blocked = listing?.errors?.find(error => error.blocked);
    const retryNotBefore = blocked?.retryNotBefore ?? restrictionRetryTime(
      blocked?.retryAfter, blocked?.checkedAt ?? listing?.finishedAt ?? listing?.observedAt, blocked?.error,
    );
    if (blocked && (!resumeAfterAccessRestored || (retryNotBefore && Date.now() < Date.parse(retryNotBefore)))) {
      return { ...report, status: "restricted", retryNotBefore, listing: listingReport() };
    }
    listing = await captureListing({ ...listingOptions, page, sourceUrl, zip, outputFile: listingPath, resume: Boolean(listing), deadlineEpochMs });
  }
  report.listing = listingReport();
  if (!listing.complete) {
    return { ...report, status: listing.errors?.some(error => error.blocked) ? "restricted" : "pending" };
  }
  if (Date.now() >= deadlineEpochMs) return report;

  report.stage = "products";
  const products = await captureProducts({
    concurrency: 1, ...productOptions, page, listingPath, outputDir: root, deadlineEpochMs, resumeAfterAccessRestored,
  });
  report.products = products;
  if (products.blockedAt) return { ...report, status: "restricted", retryNotBefore: products.retryNotBefore };
  if (products.pending || products.stopReason || Date.now() >= deadlineEpochMs) return report;
  if (products.pdpsVerified === 0) return { ...report, status: "incomplete" };

  report.stage = "quantities";
  const quantities = await captureQuantities({
    ...quantityOptions, page, listingPath, outputDir: root, deadlineEpochMs, resumeAfterAccessRestored,
  });
  report.quantities = quantities;
  if (quantities.restriction) return { ...report, status: "restricted", retryNotBefore: quantities.restriction.retryNotBefore };
  if (quantities.pendingSkus || quantities.stopReason) return report;
  return {
    ...report,
    status: quantities.listingComplete && quantities.catalogCandidatePdpsComplete &&
      quantities.catalogMembershipComplete && quantities.skuReadsComplete && quantities.skuQuantitiesComplete
      ? "complete" : "incomplete",
  };
}
