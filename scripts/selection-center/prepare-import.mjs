import { readFile, writeFile } from "node:fs/promises";

function businessDate(iso) {
    const parts = new Intl.DateTimeFormat("en-CA", {
        year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Shanghai",
    }).formatToParts(new Date(iso));
    const part = type => parts.find(entry => entry.type === type)?.value;
    return [part("year"), part("month"), part("day")].join("-");
}

export function makeImport(listing, productPages = {}, quantities = {}) {
    if (!listing.sourceUrl || !listing.observedAt || !listing.zip || !Array.isArray(listing.pages)) {
        throw new Error("Missing source, observation time, ZIP, or pages");
    }
    const date = businessDate(listing.observedAt);
    const quantitiesByProduct = new Map();
    for (const result of Object.values(quantities)) {
        if (result?.status !== "ok" || !result.productId || !result.itemId) continue;
        const found = quantitiesByProduct.get(result.productId);
        if (found === null || (found && found.itemId !== result.itemId)) {
            quantitiesByProduct.set(result.productId, null);
        } else {
            quantitiesByProduct.set(result.productId, result);
        }
    }
    const verifiedPrimaries = new Map();
    for (const product of Object.values(productPages)) {
        if (!product.variantSetKey || !["pdp-primary-confirmed-in-variants", "pdp-abstract-confirmed-by-listing"].includes(product.groupKeySource)) continue;
        const choices = verifiedPrimaries.get(product.variantSetKey) ?? new Map();
        choices.set(product.groupKey, product.representativeItemId);
        verifiedPrimaries.set(product.variantSetKey, choices);
    }
    const items = new Map();
    let allItemsObserved = true;
    for (const page of [...listing.pages].sort((a, b) => a.page - b.page)) {
        for (const item of page.items) {
            if (items.has(item.itemId)) continue;
            const product = productPages[item.itemId];
            let observedSlots = 0;
            const variants = (product?.variants ?? []).map(variant => {
                const result = quantities[`item:${variant.itemId}`] ?? quantities[`product:${variant.productId}`]
                    ?? quantitiesByProduct.get(variant.productId);
                const isObservation = result?.status === "ok" && result.observedAt &&
                    businessDate(result.observedAt) === date &&
                    (!variant.itemId || variant.itemId === result.itemId) &&
                    (!variant.productId || variant.productId === result.productId);
                if (!isObservation) {
                    // Membership metadata alone can't supply a quantity, and a prior day's quantity can't seed this batch.
                    if (!variant.observedAt || businessDate(variant.observedAt) !== date || variant.quantityIssue) {
                        return {...variant, orderLimit: null, maxOrderQuantity: null, observedAt: null};
                    }
                    return variant;
                }
                if (result.shippingStatus || result.availabilityStatus) observedSlots++;
                return {
                    ...variant, ...result,
                    orderLimit: result.verifiedQuantity == null ? null : result.orderLimit,
                    maxOrderQuantity: result.verifiedQuantity == null ? null : result.maxOrderQuantity,
                };
            }).filter(row => /^\d+$/.test(String(row.itemId)));
            const expectedSkus = ["pdp-variants", "pdp-regular"].includes(product?.membershipSource)
                ? product.expectedSkuCount : null;
            if (!expectedSkus || variants.length !== expectedSkus || observedSlots !== expectedSkus) {
                allItemsObserved = false;
            }
            const verified = product && product.groupKeySource !== "entry-item";
            const primaries = verifiedPrimaries.get(product?.variantSetKey);
            const checkedAbstract = product?.variantSetKey && product.abstractProductId &&
                product.abstractProductId === item.productId;
            let groupKey = verified ? product.groupKey : null;
            let representativeItemId = verified ? product.representativeItemId : null;
            if (checkedAbstract) {
                groupKey = "abstract:" + product.abstractProductId;
            } else if (primaries?.size === 1) {
                [groupKey, representativeItemId] = primaries.entries().next().value;
            }
            items.set(item.itemId, {
                itemId: item.itemId,
                productId: product?.productId ?? item.productId,
                primaryItemId: representativeItemId ?? null,
                styleKey: groupKey || null,
                title: item.title || product?.title || `Walmart Item ${item.itemId}`,
                brand: item.brand || product?.brand || null,
                category: item.productType || product?.productType || null,
                imageUrl: item.image || product?.image || null,
                price: item.price ?? product?.price ?? null,
                sourcePosition: item.rank,
                expectedSkus,
                variants: variants.map(row => ({
                    itemId: row.itemId,
                    productId: row.productId,
                    color: row.color ?? null,
                    size: row.size ?? null,
                    orderLimit: row.orderLimit ?? null,
                    maxOrderQuantity: row.maxOrderQuantity ?? null,
                    availability: row.shippingStatus ?? row.availabilityStatus ?? null,
                    sellerId: row.sellerId ?? null,
                    offerId: row.offerId ?? null,
                    fulfillment: row.fulfillmentType ?? null,
                    observedAt: row.observedAt ?? null,
                })),
            });
        }
    }
    if (!items.size) throw new Error("No Walmart candidates to import");
    // 消解跨款变体归属冲突：Walmart PDP 偶尔把一个子 Item 同时挂到两个不同款（如 Petite/Tall 版共用变体）。
    // Java 侧 claim() 拒绝同一 itemId 归属不同 styleKey。按 sourcePosition 保留先出现的款，
    // 从后出现的款里剔除冲突的 variant，避免整批导入失败。仅影响成员归属，不动任何库存读数。
    const orderedItems = [...items.values()].sort((a, b) => (a.sourcePosition ?? 1e9) - (b.sourcePosition ?? 1e9));
    const variantOwner = new Map();
    let resolvedConflicts = 0;
    for (const item of orderedItems) {
        item.variants = item.variants.filter(variant => {
            if (!variant.itemId) return true;
            const key = String(variant.itemId);
            if (!variantOwner.has(key)) { variantOwner.set(key, item.itemId); return true; }
            if (variantOwner.get(key) === String(item.itemId)) return true;
            resolvedConflicts++;
            return false;
        });
    }
    if (resolvedConflicts) console.error(`[prepare-import] resolved ${resolvedConflicts} cross-style variant conflicts`);
    return {
        sourceUrl: listing.sourceUrl,
        zip: listing.zip,
        date,
        observedAt: listing.observedAt,
        expectedPages: listing.maxPage,
        completedPages: new Set(listing.pages.map(page => page.page)).size,
        declaredCount: listing.declaredCount ?? null,
        issue: listing.errors?.length
            ? listing.errors.map(entry => `第 ${entry.requestedPage} 页 ${entry.error}`).join("；")
            : null,
        complete: Boolean(listing.complete),
        inventoryComplete: Boolean(listing.complete) && allItemsObserved,
        items: [...items.values()],
    };
}

const [listingFile, outputFile, productsFile, quantitiesFile] = process.argv.slice(2);
if (!listingFile || !outputFile) {
    throw new Error("Usage: node prepare-import.mjs listing.json output.json [products.json] [quantities.json]");
}
const readJson = async path => JSON.parse(await readFile(path, "utf8"));
const listing = await readJson(listingFile);
const products = productsFile ? await readJson(productsFile) : {};
const quantities = quantitiesFile ? await readJson(quantitiesFile) : {};
const result = makeImport(listing, products, quantities);
await writeFile(outputFile, JSON.stringify(result, null, 2) + "\n", "utf8");
console.log(JSON.stringify({
    date: result.date,
    candidates: result.items.length,
    withVerifiedGroup: result.items.filter(item => item.styleKey).length,
    withVariants: result.items.filter(item => item.variants.length).length,
    completedPages: result.completedPages,
    expectedPages: result.expectedPages,
    complete: result.complete,
    inventoryComplete: result.inventoryComplete,
}));
