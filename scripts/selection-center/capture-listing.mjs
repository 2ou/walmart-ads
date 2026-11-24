import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { setTimeout as wait } from "node:timers/promises";

const REQUIRED_COOLDOWN_MS = 120_000;
const shanghaiDay = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai" });

function readListing(docData, url, requestedPage, zip) {
    const data = docData?.props?.pageProps?.initialData;
    const search = data?.searchResult;
    if (!search || !Array.isArray(search.itemStacks)) {
        return { requestedPage, error: "Missing Walmart listing data" };
    }
    const pagination = search.paginationV2;
    const page = pagination?.pageProperties?.page || 1;
    const location = data.pageMetadata?.location;
    if (page !== requestedPage || location?.postalCode !== zip) {
        return { requestedPage, error: "Unexpected page or ZIP" };
    }
    const products = search.itemStacks
        .flatMap(stack => stack.items ?? [])
        .filter(item => item.__typename === "Product" && /^\d+$/.test(String(item.usItemId)));
    const items = products.map((item, index) => {
        const dollars = item.priceInfo?.linePrice?.match(/^\$([\d,.]+)$/)?.[1];
        const image = item.imageInfo?.thumbnailUrl ?? item.image ?? null;
        return {
            page,
            pagePosition: index + 1,
            rank: 40 * (page - 1) + index + 1,
            itemId: String(item.usItemId),
            productId: item.id ?? null,
            title: item.name ?? item.displayName ?? null,
            brand: item.brand ?? null,
            productType: item.catalogProductType ?? null,
            image: typeof image === "string" ? image : null,
            price: dollars ? Number(dollars.replaceAll(",", "")) : null,
            href: new URL(item.canonicalUrl ?? ("/ip/" + item.usItemId), url).href,
            sponsored: Boolean(item.isSponsoredFlag || item.sponsoredProduct),
            rating: item.averageRating ?? null,
            reviews: item.numberOfReviews ?? null,
        };
    });
    return {
        page, url, items, zip: location.postalCode,
        city: location.city, state: location.stateOrProvinceCode,
        maxPage: pagination?.maxPage,
        declaredCount: search.aggregatedCount,
        sort: pagination?.pageProperties?.sort,
    };
}

async function capturePages(page, requests) {
    const evaluate = `async ({requests}) => {
      const readListing = ${readListing.toString()};
      return Promise.all(requests.map(async ({url, page, zip}) => {
        try {
          const res = await fetch(url, {credentials: "same-origin", signal: AbortSignal.timeout(15000)});
          const blocked = [401, 403, 412, 418, 429].includes(res.status);
          if (!res.ok) {
            return {requestedPage: page, error: "HTTP " + res.status, blocked,
                retryAfter: res.headers.get("retry-after"), checkedAt: new Date().toISOString()};
          }
          const html = await res.text();
          const doc = new DOMParser().parseFromString(html, "text/html");
          const data = doc.getElementById("__NEXT_DATA__")?.textContent;
          if (!data && (/robot|captcha|verif/i.test(doc.title) || /verify you are human|press & hold/i.test(doc.body?.textContent?.slice(0,3000) ?? ""))) {
                return {requestedPage: page, error: "Walmart human verification", blocked: true};
          }
          return readListing(JSON.parse(data ?? "null"), res.url, page, zip);
        } catch (error) {
          return {requestedPage: page, error: String(error)};
        }
      }));
    }`;
    return page.evaluate(`(${evaluate})(${JSON.stringify({requests})})`);
}

export async function captureListing({
    page, sourceUrl, zip, outputFile, resume = false,
    concurrency = 1, paceMs = 1000, maxRunMs = Infinity, deadlineEpochMs = Infinity,
}) {
    if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 3 ||
        !Number.isFinite(paceMs) || paceMs < 750 || !(maxRunMs > 0)) {
        throw new Error("Invalid listing request pacing or duration");
    }
    const started = Date.now();
    const actualUrl = new URL(await page.url());
    const source = new URL(sourceUrl);
    if (actualUrl.origin !== source.origin || actualUrl.pathname !== source.pathname ||
        actualUrl.searchParams.get("facet") !== source.searchParams.get("facet")) {
        throw new Error("Open the specified Walmart listing in the Ego page first");
    }
    const first = await page.evaluate(
        `(${readListing.toString()})(window.__NEXT_DATA__, location.href, 1, ${JSON.stringify(zip)})`,
    );
    if (first.error || !first.items?.length || !Number.isInteger(first.maxPage)) {
        throw new Error(first.error ?? "Could not identify the first page of Walmart results");
    }
    const fresh = {
        sourceUrl, zip, city: first.city, state: first.state, sort: first.sort,
        observedAt: new Date().toISOString(),
        declaredCount: first.declaredCount, maxPage: first.maxPage,
        pages: [first], errors: [], complete: false,
    };
    const listing = resume ? JSON.parse(await readFile(outputFile, "utf8")) : fresh;
    if (shanghaiDay.format(new Date(listing.observedAt)) !== shanghaiDay.format(new Date())) {
        throw new Error("The listing snapshot day has ended");
    }
    if (resume && (listing.sourceUrl !== sourceUrl || listing.zip !== zip ||
        listing.sort !== first.sort || listing.maxPage !== first.maxPage ||
        listing.declaredCount !== first.declaredCount ||
        !listing.pages[0]?.items.every((item, i) => item.itemId === first.items[i]?.itemId))) {
        throw new Error("Stored listing no longer matches current source metadata; start a new capture");
    }
    await mkdir(dirname(outputFile), {recursive: true});
    const save = async () => {
        await writeFile(outputFile + ".tmp", JSON.stringify(listing, null, 2));
        await rename(outputFile + ".tmp", outputFile);
    };
    await save();
    const pageSize = first.items.length;
    const stopDuringRestriction = listing.errors.find(entry => entry.blocked);
    if (stopDuringRestriction) {
        if (stopDuringRestriction.error !== "HTTP 429") {
            throw new Error("Walmart access must be restored before retry");
        }
        const checkedAt = Date.parse(stopDuringRestriction.checkedAt || listing.finishedAt);
        const header = stopDuringRestriction.retryAfter;
        const headerUntil = header && /^\d+$/.test(header)
            ? checkedAt + Number(header) * 1000
            : Date.parse(header);
        const until = Number.isFinite(headerUntil) ? headerUntil : checkedAt + REQUIRED_COOLDOWN_MS;
        if (!Number.isFinite(until) || Date.now() < until) {
            throw new Error("Walmart request cooldown has not elapsed");
        }
    }
    for (let start = 2; start <= first.maxPage; start += concurrency) {
        if (Date.now() >= Math.min(started + maxRunMs, deadlineEpochMs) ||
            shanghaiDay.format(new Date(listing.observedAt)) !== shanghaiDay.format(new Date())) break;
        const requests = [];
        for (let n = start; n < Math.min(start + concurrency, first.maxPage + 1); n++) {
            if (resume && listing.pages.some(p => p.page === n)) continue;
            const url = new URL(sourceUrl);
            url.searchParams.set("page", String(n));
            requests.push({page: n, url: url.href, zip});
        }
        if (!requests.length) continue;
        const results = await capturePages(page, requests);
        for (const result of results) {
            const target = result.page ?? result.requestedPage;
            if (resume) {
                listing.priorErrors ??= [];
                listing.priorErrors.push(...listing.errors.filter(e => e.requestedPage === target));
                listing.errors = listing.errors.filter(e => e.requestedPage !== target);
            }
            if (result.error) listing.errors.push(result);
            else {
                const expectedCount = Math.min(pageSize, listing.declaredCount - pageSize * (result.page - 1));
                if (result.declaredCount !== listing.declaredCount || result.sort !== listing.sort ||
                    result.maxPage !== listing.maxPage || result.items.length !== expectedCount) {
                    listing.errors.push({
                        requestedPage: result.page,
                        error: "Listing count, sort, or pagination changed during capture",
                        declaredCount: result.declaredCount,
                        capturedItems: result.items.length,
                    });
                }
                for (const item of result.items) {
                    item.rank = pageSize * (result.page - 1) + item.pagePosition;
                }
                listing.pages.push(result);
            }
        }
        listing.pages.sort((a, b) => a.page - b.page);
        await save();
        if (results.some(result => result.blocked)) break;
        if (start + concurrency <= first.maxPage) await wait(paceMs);
    }
    listing.finishedAt = new Date().toISOString();
    const items = listing.pages.flatMap(p => p.items);
    listing.cardCount = items.length;
    listing.uniqueItemCount = new Set(items.map(i => i.itemId)).size;
    listing.complete = listing.pages.length === listing.maxPage && !listing.errors.length &&
      items.length === listing.declaredCount && listing.uniqueItemCount === items.length;
    await save();
    return listing;
}
