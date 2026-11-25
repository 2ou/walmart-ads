package com.ai.walmartads.walmartlisting;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.inventory.application.LxInventorySnapshotWriter;
import com.ai.walmartads.inventory.domain.LxInventorySnapshot;
import com.ai.walmartads.inventory.repository.LxInventorySnapshotRepository;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.LingXingMcpCoordinator;
import com.ai.walmartads.walmartlisting.domain.WalmartListingSyncLog;
import com.ai.walmartads.walmartlisting.repository.WalmartListingSyncLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 沃尔玛在线商品 MCP 每小时同步：拉取领星 {@code platform_listing_walmart_list} 全量（翻页）
 * 并合并 {@code wfs_stock_v2_list}（WFS 库龄、预留拆解、官方预测与冗余、采购成本），
 * 按 SKU 回写产品库运营列（只更新已存在行，不新建 SKU），并落一条同步记录。
 *
 * <p>营销属性列（color/size/pattern/material/key_features 等）一律不动，仅 MCP 给得动的运营字段被回写。
 */
@Service
public class WalmartListingSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalmartListingSyncService.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private final LingXingProperties properties;
    private final ProductRecordRepository productRepository;
    private final WalmartListingSyncLogRepository logRepository;
    private final ObjectMapper objectMapper;
    private final LxInventorySnapshotWriter inventorySnapshotWriter;
    private final SkuMappingSyncService skuMappingSyncService;
    @Autowired
    private WorkspaceLingXingConfigService workspaceConfig;
    @Autowired
    private LingXingMcpCoordinator mcpCoordinator;
    @Autowired(required = false)
    private com.ai.walmartads.product.application.ProductCatalogService productCatalogService;
    @Autowired(required = false)
    private LxInventorySnapshotRepository snapshotRepository;

    public WalmartListingSyncService(
            LingXingProperties properties,
            ProductRecordRepository productRepository,
            WalmartListingSyncLogRepository logRepository,
            ObjectMapper objectMapper,
            LxInventorySnapshotWriter inventorySnapshotWriter,
            SkuMappingSyncService skuMappingSyncService
    ) {
        this.properties = properties;
        this.productRepository = productRepository;
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
        this.inventorySnapshotWriter = inventorySnapshotWriter;
        this.skuMappingSyncService = skuMappingSyncService;
    }

    /** 当前最新业务日的库存快照是否已包含 WFS Stock V2 扩展字段（用于启动自动补跑判断）。 */
    public boolean hasWfsStockV2Snapshot() {
        if (snapshotRepository == null) {
            return true;
        }
        LocalDate latest = snapshotRepository.findLatestBusinessDate();
        if (latest == null) {
            return false;
        }
        return snapshotRepository.countWfsStockV2ByBusinessDate(latest) > 0;
    }

    public SyncSummary syncNow(String triggeredBy) {
        WorkspaceLingXingConfigService.Config config = workspaceConfig.current();
        if (!config.configured()) {
            throw new IllegalStateException("领星 MCP 未配置，无法同步");
        }
        LingXingClient client = new LingXingClient(config.baseUrl(), config.apiKey(), config.pageSize(),
                10, 30, objectMapper, List.of(), "PRODUCTS", mcpCoordinator);
        WalmartListingSyncLog record = new WalmartListingSyncLog(LocalDateTime.now(), "lingxing_mcp", triggeredBy);
        logRepository.save(record);
        long start = System.currentTimeMillis();
        try {
            List<WalmartListingDto> listings = client.fetchAllListings(config.storeId());
            if (listings.isEmpty()) {
                throw new LxInventorySnapshotWriter.IncompleteSnapshotException(
                        "领星 Listing 返回 0 条，已按异常空结果处理并保留原商品与库存数据");
            }
            Map<String, WalmartListingDto> bySku = listings.stream()
                    .filter(d -> d.sku != null && !d.sku.isBlank())
                    .collect(Collectors.toMap(
                            d -> d.sku,
                            d -> d,
                            (first, second) -> first
                    ));
            // Validate before mutating product rows. A truncated first page must fail the whole sync
            // instead of being recorded as success and replacing the inventory snapshot.
            // 日期必须与 writeInventorySnapshot 用的业务日一致，否则校验与写入会错位一天。
            inventorySnapshotWriter.assertComplete(UsBusinessDate.usToday(), bySku.size());
            Map<String, WalmartWfsStockDto> wfsBySku = fetchWfsStockSafely(client, config.storeId());
            List<ProductRecord> products = productRepository
                    .findAllBySkuInAndCurrentVersionTrue(bySku.keySet());
            int updated = 0;
            List<ProductRecord> dirtyProducts = new ArrayList<>();
            for (ProductRecord product : products) {
                WalmartListingDto dto = bySku.get(product.getSku());
                if (dto == null) {
                    continue;
                }
                if (applyTo(product, dto, wfsBySku.get(product.getSku()))) {
                    dirtyProducts.add(product);
                }
                updated++;
            }
            if (!dirtyProducts.isEmpty()) {
                productRepository.saveAll(dirtyProducts);
                if (productCatalogService != null) {
                    productCatalogService.invalidate();
                }
            }
            writeInventorySnapshot(bySku, products, wfsBySku);
            String mappingError = rewriteSkuMapping(listings);
            long duration = System.currentTimeMillis() - start;
            record.setFetchedCount(listings.size());
            record.setUpdatedCount(updated);
            record.setInsertedCount(0);
            if (mappingError != null) {
                record.setErrorMessage(truncate(mappingError));
                record.setStatus("PARTIAL");
            } else {
                // 拉到数据但产品库无一匹配：口径异常（产品库应已含沃尔玛在售 SKU），标记 PARTIAL 提示排查。
                record.setStatus(updated == 0 && !listings.isEmpty() ? "PARTIAL" : "SUCCESS");
            }
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.info("沃尔玛在线商品同步完成：fetched={}, updated={}, duration={}ms", listings.size(), updated, duration);
            return new SyncSummary(listings.size(), updated, 0, record.getStatus(),
                    record.getErrorMessage(), duration, LocalDateTime.now());
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - start;
            record.setStatus("FAILED");
            record.setErrorMessage(truncate(exception.getMessage()));
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.error("沃尔玛在线商品同步失败：{}", exception.getMessage());
            return new SyncSummary(0, 0, 0, "FAILED", truncate(exception.getMessage()), duration, LocalDateTime.now());
        }
    }

    /**
     * 安全拉取 WFS 库存列表-v2：异常或限流时只记告警并降级为空 Map，不阻塞在线商品与价格监控主链路。
     */
    private Map<String, WalmartWfsStockDto> fetchWfsStockSafely(LingXingClient client, String storeId) {
        try {
            List<WalmartWfsStockDto> items = client.fetchAllWfsStockV2(storeId);
            Map<String, WalmartWfsStockDto> bySku = new LinkedHashMap<>();
            for (WalmartWfsStockDto item : items) {
                String key = blankToNull(item.msku);
                if (key == null) {
                    key = blankToNull(item.sku);
                }
                if (key != null) {
                    bySku.putIfAbsent(key.trim(), item);
                }
            }
            return bySku;
        } catch (Exception exception) {
            log.warn("领星 WFS 库存 v2 拉取失败（将自动继承当日历史明细，不阻塞商品主同步）：{}", exception.getMessage());
            return Map.of();
        }
    }

    /**
     * 领星库存快照（V49 + V93）：以本次在线商品与 WFS v2 合并结果整窗替换「当天」快照。
     *
     * <p>可售量口径 = {@code wfs_available_quantity + available_quantity}（与沃尔玛后台报表逐 SKU 实测一致）；
     * WFS 扩展明细（库龄 5 档、预留锁仓拆解、官方预测/冗余/售罄率/断货日、采购成本）来自 {@code wfs_stock_v2_list}。
     * 若本次 WFS v2 拉取失败，自动继承当天上一版快照中的扩展明细。
     */
    private void writeInventorySnapshot(
            Map<String, WalmartListingDto> bySku,
            List<ProductRecord> products,
            Map<String, WalmartWfsStockDto> wfsBySku
    ) {
        try {
            Map<String, ProductRecord> productBySku = products.stream()
                    .filter(product -> product.getSku() != null)
                    .collect(Collectors.toMap(
                            ProductRecord::getSku,
                            product -> product,
                            (first, second) -> first
                    ));
            // 库存快照的 business_date 必须是美西业务日：领星 listing 反映的是沃尔玛当前时点库存，
            // 而订单/广告的 report_date 也都是美西日历日。用服务器本地日期（北京）会让快照
            // 比销量数据提前一天，跨日对账时出现「当天有销量但没库存」的假缺口。
            LocalDate businessDate = UsBusinessDate.usToday();
            LocalDateTime snapshotAt = LocalDateTime.now();
            Map<String, LxInventorySnapshot> previousBySku = Map.of();
            if ((wfsBySku == null || wfsBySku.isEmpty()) && snapshotRepository != null) {
                previousBySku = snapshotRepository.findByBusinessDate(businessDate).stream()
                        .filter(existing -> existing.getSku() != null)
                        .collect(Collectors.toMap(
                                LxInventorySnapshot::getSku,
                                existing -> existing,
                                (first, second) -> first
                        ));
            }
            List<LxInventorySnapshot> rows = new ArrayList<>(bySku.size());
            for (WalmartListingDto dto : bySku.values()) {
                ProductRecord product = productBySku.get(dto.sku);
                LxInventorySnapshot row = new LxInventorySnapshot(businessDate, snapshotAt, dto.sku)
                        .withParentSku(product == null ? null : product.getParentSku())
                        .withItemId(blankToNull(dto.itemId))
                        .withGtin(blankToNull(dto.gtin))
                        .withItemName(product == null ? null : product.getProductName())
                        .withBrand(product == null ? null : product.getBrand())
                        .withListingStatus(mapStatus(dto.status))
                        .withQuantities(dto.wfsAvailable, dto.nonWfsAvailable);
                WalmartWfsStockDto wfs = wfsBySku == null ? null : wfsBySku.get(dto.sku);
                if (wfs != null) {
                    applyWfsDetails(row, wfs, product);
                } else {
                    LxInventorySnapshot previous = previousBySku.get(dto.sku);
                    if (previous != null) {
                        row.copyWfsDetailsFrom(previous);
                    }
                    if (row.getPurchaseCost() == null && product != null && product.getPurchaseCost() != null) {
                        row.setPurchaseCost(product.getPurchaseCost());
                    }
                }
                rows.add(row);
            }
            int written = inventorySnapshotWriter.replaceDay(businessDate, snapshotAt, rows);
            log.info("领星库存快照已整窗替换：businessDate={}, rows={}, wfsMatched={}",
                    businessDate, written, wfsBySku == null ? 0 : wfsBySku.size());
        } catch (LxInventorySnapshotWriter.IncompleteSnapshotException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("领星库存快照写入失败（不影响在线商品同步）：{}", exception.getMessage());
        }
    }

    private static void applyWfsDetails(LxInventorySnapshot row, WalmartWfsStockDto wfs, ProductRecord product) {
        row.setReservedUnits(parseFlexInt(wfs.reservedQuantity));
        row.setInboundUnits(parseFlexInt(wfs.inboundQuantity));
        row.setSuggestedUnits(parseFlexInt(wfs.suggestedUnits));
        row.setSurplusUnits(parseFlexInt(wfs.surplusUnits));
        row.setForecastedDaysSupply(parseDaysOfSupply(wfs.daysOfSupply));
        row.setOutOfStockDate(parseLocalDate(wfs.outOfStockDate));
        row.setSellThroughRate(parseDecimal(wfs.sellThroughRate));
        row.setItemLifecycle(blankToNull(wfs.itemLifecycle));
        row.setOnhandUnits(parseFlexInt(wfs.onhandQuantity));
        row.setUnabledWarehousingUnits(parseFlexInt(wfs.unabledWarehousingQuantity));
        row.setInventoryReviewUnits(parseFlexInt(wfs.inventoryReviewQuantity));
        row.setDamagedUnits(parseFlexInt(wfs.damagedQuantity));
        row.setAts03Months(parseFlexInt(wfs.ats03Months));
        row.setAts36Months(parseFlexInt(wfs.ats36Months));
        row.setAts69Months(parseFlexInt(wfs.ats69Months));
        row.setAts912Months(parseFlexInt(wfs.ats912Months));
        row.setAts1YearPlus(parseFlexInt(wfs.ats1Years));
        row.setFirstInStockDate(parseLocalDate(wfs.firstInStockDate));
        row.setSalesForecastW1To4(parseDecimal(wfs.salesForecastWeek1to4));
        row.setSalesForecastW5To8(parseDecimal(wfs.salesForecastWeek5to8));
        row.setSalesForecastW9To12(parseDecimal(wfs.salesForecastWeek9to12));
        row.setWfsStockStatus(blankToNull(wfs.stockStatus));
        row.setCustomerFavorite(wfs.customerFavorite);
        BigDecimal cost = parsePositiveDecimal(wfs.purchasePrice);
        if (cost != null) {
            row.setPurchaseCost(cost);
        } else if (product != null && product.getPurchaseCost() != null) {
            row.setPurchaseCost(product.getPurchaseCost());
        }
    }

    /**
     * 顺带用本次在线商品结果整窗重写 SKU 映射（{@code wm_ads_sku_mapping}）—— 共用同一批数据，零额外 API 调用。
     *
     * <p>自 2026-09-28 起该表的数据源由本地 WFS 上传模板换成此处，故本方法必须成功才有映射数据。
     * 但映射重写走独立事务，失败只记录并标记 PARTIAL，不影响在线商品与库存快照这两个主产物。
     *
     * @return 失败信息；成功返回 {@code null}
     */
    private String rewriteSkuMapping(List<WalmartListingDto> listings) {
        try {
            SkuMappingSyncService.RewriteSummary summary = skuMappingSyncService.rewrite(listings);
            log.info(
                    "SKU 映射同步：normalized={}, written={}, skipped={}",
                    summary.normalizedRows(),
                    summary.writtenRows(),
                    summary.skipped()
            );
            return null;
        } catch (Exception exception) {
            log.error("SKU 映射同步失败（不影响在线商品同步）：{}", exception.getMessage());
            return exception.getMessage();
        }
    }

    private boolean applyTo(ProductRecord product, WalmartListingDto d, WalmartWfsStockDto wfs) {
        boolean changed = false;
        changed |= updateDecimal(product.getPrice(), parseDecimal(d.price), product::setPrice);
        changed |= updateDecimal(product.getStrikethroughPrice(), parseDecimal(d.strikethroughPrice), product::setStrikethroughPrice);
        changed |= updateValue(product.getUpc(), blankToNull(d.upc), product::setUpc);
        changed |= updateValue(product.getGtin(), blankToNull(d.gtin), product::setGtin);
        changed |= updateValue(product.getListingStatus(), mapStatus(d.status), product::setListingStatus);
        changed |= updateValue(product.getStore(), blankToNull(d.store), product::setStore);
        if (d.itemId != null && !d.itemId.isBlank()) {
            changed |= updateValue(product.getProductId(), d.itemId, product::setProductId);
        }
        changed |= updateValue(product.getWfsAvailable(), d.wfsAvailable, product::setWfsAvailable);
        changed |= updateValue(product.getNonWfsAvailable(), d.nonWfsAvailable, product::setNonWfsAvailable);
        changed |= updateDecimal(product.getBuyBoxPrice(), parseDecimal(d.buyBoxPrice), product::setBuyBoxPrice);
        changed |= updateValue(product.getReviewCount(), parseInt(d.reviewCount), product::setReviewCount);
        changed |= updateDecimal(product.getAverageRating(), parseDecimal(d.averageRating), product::setAverageRating);
        changed |= updateDecimal(product.getCompetitorPrice(), parseDecimal(d.competitorPrice), product::setCompetitorPrice);
        changed |= updateValue(product.getFulfillmentType(), d.fulfillmentType, product::setFulfillmentType);
        changed |= updateValue(product.getFulfillmentTypeName(), blankToNull(d.fulfillmentTypeName), product::setFulfillmentTypeName);
        changed |= updateValue(product.getListingStartTime(), parseDateTime(d.listingStartTime), product::setListingStartTime);
        changed |= updateValue(product.getOfferEndDate(), parseDateTime(d.offerEndDate), product::setOfferEndDate);
        changed |= updateValue(product.getItemUrl(), blankToNull(d.itemUrl), product::setItemUrl);
        changed |= updateValue(product.getPromotionJson(), promotionJson(d.promotionDataList), product::setPromotionJson);
        if (wfs != null) {
            BigDecimal purchaseCost = parsePositiveDecimal(wfs.purchasePrice);
            if (purchaseCost != null) {
                changed |= updateDecimal(product.getPurchaseCost(), purchaseCost, product::setPurchaseCost);
            }
        }
        if (changed) {
            product.setLastSyncedAt(LocalDateTime.now());
        }
        return changed;
    }

    private static <T> boolean updateValue(T current, T next, java.util.function.Consumer<T> setter) {
        if (java.util.Objects.equals(current, next)) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    private static boolean updateDecimal(BigDecimal current, BigDecimal next, java.util.function.Consumer<BigDecimal> setter) {
        if (current == null && next == null) {
            return false;
        }
        if (current != null && next != null && current.compareTo(next) == 0) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    private static BigDecimal parseDecimal(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseFlexInt(String value) {
        BigDecimal dec = parseDecimal(value);
        return dec == null ? null : dec.intValue();
    }

    private static BigDecimal parsePositiveDecimal(String value) {
        BigDecimal dec = parseDecimal(value);
        return (dec != null && dec.compareTo(BigDecimal.ZERO) > 0) ? dec : null;
    }

    private static BigDecimal parseDaysOfSupply(String value) {
        BigDecimal direct = parseDecimal(value);
        if (direct != null) {
            return direct;
        }
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(v);
        if (matcher.find()) {
            try {
                return new BigDecimal(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate parseLocalDate(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        String trimmed = v.trim();
        if (trimmed.length() >= 10) {
            try {
                return LocalDate.parse(trimmed.substring(0, 10));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDateTime parseDateTime(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.trim(), DATE_TIME);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String mapStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "PUBLISHED";
            case 1 -> "UNPUBLISHED";
            default -> "UNKNOWN:" + status;
        };
    }

    private String promotionJson(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    public record SyncSummary(
            int fetchedCount,
            int updatedCount,
            int insertedCount,
            String status,
            String errorMessage,
            long durationMs,
            LocalDateTime finishedAt
    ) {
    }

    public Map<String, Object> toView(WalmartListingSyncLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("startedAt", log.getStartedAt());
        view.put("finishedAt", log.getFinishedAt());
        view.put("status", log.getStatus());
        view.put("source", log.getSource());
        view.put("fetchedCount", log.getFetchedCount());
        view.put("updatedCount", log.getUpdatedCount());
        view.put("insertedCount", log.getInsertedCount());
        view.put("errorMessage", log.getErrorMessage());
        view.put("triggeredBy", log.getTriggeredBy());
        view.put("durationMs", log.getDurationMs());
        return view;
    }
}
