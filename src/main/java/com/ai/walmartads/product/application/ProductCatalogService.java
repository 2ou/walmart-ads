package com.ai.walmartads.product.application;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.inventory.application.ParentLifecycleService;
import com.ai.walmartads.product.domain.ProfitAssumption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductCatalogService {

    private static final List<String> APPAREL_SIZE_ORDER = List.of(
            "XXS", "XS", "S", "M", "L", "XL",
            "1X", "XXL", "2XL", "2X", "XXXL", "3XL", "3X",
            "4XL", "4X", "5XL", "5X", "6XL", "6X", "ONE SIZE", "OS"
    );
    private static final BigDecimal EXCHANGE_RATE = new BigDecimal("7");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ProfitCalculator profitCalculator;
    private final ProfitAssumptionRepository profitAssumptionRepository;
    private final RefundRateService refundRateService;
    private final ProductRecordRepository productRecordRepository;
    private final ParentLifecycleService lifecycleService;

    public ProductCatalogService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ProfitCalculator profitCalculator,
            ProfitAssumptionRepository profitAssumptionRepository,
            RefundRateService refundRateService,
            ProductRecordRepository productRecordRepository,
            ParentLifecycleService lifecycleService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.profitCalculator = profitCalculator;
        this.profitAssumptionRepository = profitAssumptionRepository;
        this.refundRateService = refundRateService;
        this.productRecordRepository = productRecordRepository;
        this.lifecycleService = lifecycleService;
    }

    @Transactional(readOnly = true)
    public CatalogPayload load() {
        List<SourceProduct> rows = loadRows(null);
        Map<String, BigDecimal> historyRates = refundRateService.ratesByParent();
        Map<String, ParentLifecycleService.LifecycleView> lifecycleByParent = lifecycleService.viewsByParent();
        Map<String, List<SourceProduct>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        SourceProduct::parentSku,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<ProductSummary> products = groups.entrySet().stream()
                .map(entry -> summary(entry.getKey(), entry.getValue(), historyRates, profitCalculator,
                        lifecycleByParent.getOrDefault(entry.getKey(), ParentLifecycleService.LifecycleView.defaultFor(entry.getKey()))))
                .sorted(Comparator
                        .comparing(ProductSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProductSummary::parentSku))
                .toList();

        int sourceFiles = (int) rows.stream().map(SourceProduct::sourceFileName).filter(ProductCatalogService::present).distinct().count();
        LocalDateTime latestUpdated = rows.stream()
                .map(SourceProduct::sourceUpdatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new CatalogPayload(
                new CatalogMeta(
                        groups.size(),
                        rows.size(),
                        sourceFiles,
                        latestUpdated
                ),
                products
        );
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(String parentSku) {
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        List<SourceProduct> rows = loadRows(safeParentSku);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("产品库中找不到父 SKU：" + safeParentSku);
        }
        Map<String, BigDecimal> historyRates = refundRateService.ratesByParent();
        ParentLifecycleService.LifecycleView lifecycle = lifecycleService.viewFor(safeParentSku);
        SourceProduct representative = representative(rows);
        ProfitView representativeProfit = computeProfit(representative, historyRates, profitCalculator);
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.add(row.swatchImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                })
                .toList());
        List<Variant> variants = rows.stream()
                .sorted(variantComparator())
                .map(row -> toVariant(row, historyRates, profitCalculator))
                .toList();
        List<ColorGroup> colorGroups = colorGroups(rows, historyRates, profitCalculator);
        ProfitAssumptionView assumptionView = new ProfitAssumptionView(
                representative.purchaseCost(),
                representative.firstLeg(),
                representative.salesQty(),
                representative.adCost(),
                representative.manualRefundRate()
        );
        return new ProductDetail(
                safeParentSku,
                representative.productName(),
                representative.brand(),
                representative.productType(),
                representative.shortDescription(),
                representative.keyFeatures(),
                representative.mainImageUrl(),
                images,
                rows.size(),
                distinctCount(rows, SourceProduct::color),
                distinctCount(rows, SourceProduct::size),
                representative.sourceFileName(),
                representative.sourceUpdatedAt(),
                attributes(representative, representativeProfit),
                representative.rawPayload(),
                colorGroups,
                variants,
                assumptionView,
                representativeProfit.refundRate(),
                representativeProfit.refundRateSource(),
                lifecycle.clearance(),
                lifecycle.allSeason(),
                lifecycle.seasonType()
        );
    }

    @Transactional
    public void updateCuttingYield(Long id, Integer cuttingYield) {
        validateCuttingYield(cuttingYield);
        ProductRecord record = productRecordRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("产品记录不存在：" + id));
        record.setCuttingYield(cuttingYield);
        productRecordRepository.save(record);
    }

    @Transactional
    public void updateCuttingYieldByParent(String parentSku, Integer cuttingYield) {
        validateCuttingYield(cuttingYield);
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        List<ProductRecord> records = productRecordRepository.findByParentSku(safeParentSku);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("产品库中找不到父 SKU：" + safeParentSku);
        }
        for (ProductRecord record : records) {
            record.setCuttingYield(cuttingYield);
        }
        productRecordRepository.saveAll(records);
    }

    private static void validateCuttingYield(Integer cuttingYield) {
        if (cuttingYield == null) {
            return;
        }
        if (cuttingYield < 0) {
            throw new IllegalArgumentException("裁床数必须是非负整数；0 表示淘汰款，不维护时请留空。");
        }
    }

    /**
     * 回填开售时间：按父款取订单表（仅 active 导入文件）最早成交日，写入该父款下所有产品记录。
     * 无订单的父款保持 launch_date 为 null（新品）。
     */
    @Transactional
    public int backfillLaunchDates() {
        List<Map<String, Object>> firstSales = jdbc.queryForList("""
                select o.parent_sku as parent_sku,
                       min(coalesce(o.order_date, o.report_date)) as first_sale
                  from wm_ads_order_record o
                  join wm_ads_import_file f
                    on f.id = o.import_file_id
                   and f.active = true
                 where o.parent_sku is not null
                 group by o.parent_sku
                """, new MapSqlParameterSource());
        int updated = 0;
        for (Map<String, Object> row : firstSales) {
            String parentSku = (String) row.get("parent_sku");
            LocalDate firstSale = localDate(row.get("first_sale"));
            if (parentSku == null || firstSale == null) {
                continue;
            }
            List<ProductRecord> records = productRecordRepository.findByParentSku(parentSku);
            if (records.isEmpty()) {
                continue;
            }
            for (ProductRecord record : records) {
                record.setLaunchDate(firstSale);
            }
            productRecordRepository.saveAll(records);
            updated += records.size();
        }
        return updated;
    }

    @Transactional
    public ProfitAssumptionView saveProfitAssumption(SaveProfitAssumptionRequest request) {
        String parentSku = request.parentSku();
        if (parentSku == null || parentSku.isBlank()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        ProfitAssumption entity = profitAssumptionRepository.findById(parentSku.trim())
                .orElseGet(() -> new ProfitAssumption(parentSku.trim()));
        entity.setPurchaseCost(request.purchaseCost());
        entity.setFirstLeg(request.firstLeg());
        entity.setSalesQty(request.salesQty());
        entity.setAdCost(request.adCost());
        entity.setRefundRate(request.refundRate());
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        profitAssumptionRepository.save(entity);
        return new ProfitAssumptionView(
                entity.getPurchaseCost(),
                entity.getFirstLeg(),
                entity.getSalesQty(),
                entity.getAdCost(),
                entity.getRefundRate()
        );
    }

    private static List<ColorGroup> colorGroups(List<SourceProduct> rows, Map<String, BigDecimal> historyRates, ProfitCalculator calculator) {
        Map<String, List<SourceProduct>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(colorKey(row.color()), ignored -> new ArrayList<>()).add(row));
        return grouped.values().stream()
                .map(rowsForColor -> colorGroup(rowsForColor, historyRates, calculator))
                .sorted(Comparator
                        .comparing(ColorGroup::primaryColor).reversed()
                        .thenComparing(ColorGroup::color, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static ColorGroup colorGroup(List<SourceProduct> rows, Map<String, BigDecimal> historyRates, ProfitCalculator calculator) {
        SourceProduct representative = representative(rows);
        List<Variant> variants = rows.stream()
                .sorted(colorVariantComparator())
                .map(row -> toVariant(row, historyRates, calculator))
                .toList();
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                })
                .toList());
        List<String> sizes = variants.stream()
                .map(Variant::size)
                .filter(ProductCatalogService::present)
                .distinct()
                .sorted(apparelSizeComparator())
                .toList();
        return new ColorGroup(
                present(representative.color()) ? representative.color().trim() : "未标注颜色",
                representative.colorCategory(),
                representative.patternName(),
                representative.mainImageUrl(),
                representative.swatchImageUrl(),
                images,
                sizes,
                variants.size(),
                variants.stream().anyMatch(Variant::primaryVariant),
                variants
        );
    }

    private static Variant toVariant(SourceProduct row, Map<String, BigDecimal> historyRates, ProfitCalculator calculator) {
        ProfitView profitView = computeProfit(row, historyRates, calculator);
        List<String> images = new ArrayList<>();
        images.add(row.mainImageUrl());
        images.addAll(row.secondaryImageUrls());
        return new Variant(
                row.id(),
                row.sku(),
                row.cuttingYield(),
                row.productId(),
                row.productIdType(),
                row.color(),
                row.colorCategory(),
                row.size(),
                row.sizeGroup(),
                row.patternName(),
                row.materialName(),
                row.mainImageUrl(),
                row.swatchImageUrl(),
                distinctStrings(images),
                row.primaryVariant(),
                row.sourceUpdatedAt(),
                row.itemId(),
                row.upc(),
                row.price(),
                row.shippingWeightLb(),
                row.shippingWeightUnit(),
                row.shippingWeightKg(),
                row.deliveryFee(),
                profitView.profit(),
                profitView.margin(),
                profitView.refundRate(),
                profitView.refundRateSource()
        );
    }

    private static ProfitView computeProfit(SourceProduct row, Map<String, BigDecimal> historyRates, ProfitCalculator calculator) {
        BigDecimal history = historyRates.get(row.parentSku());
        BigDecimal manual = row.manualRefundRate();
        BigDecimal refundRate;
        String source;
        if (history != null) {
            refundRate = history;
            source = "HISTORY";
        } else if (manual != null) {
            refundRate = manual;
            source = "MANUAL";
        } else {
            refundRate = BigDecimal.ZERO;
            source = "DEFAULT";
        }
        ProfitCalculator.ProfitResult result = calculator.compute(
                row.price(),
                row.deliveryFee(),
                row.purchaseCost(),
                row.firstLeg(),
                row.salesQty(),
                row.adCost(),
                refundRate,
                EXCHANGE_RATE
        );
        if (result == null) {
            return new ProfitView(null, null, refundRate, source);
        }
        return new ProfitView(result.profit(), result.margin(), refundRate, source);
    }

    private static Comparator<SourceProduct> variantComparator() {
        return Comparator
                .comparing(SourceProduct::primaryVariant).reversed()
                .thenComparing(SourceProduct::color, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(SourceProduct::size, Comparator.nullsLast(apparelSizeComparator()))
                .thenComparing(SourceProduct::sku);
    }

    private static Comparator<SourceProduct> colorVariantComparator() {
        return Comparator
                .comparing(SourceProduct::size, Comparator.nullsLast(apparelSizeComparator()))
                .thenComparing(SourceProduct::sku);
    }

    private static Comparator<String> apparelSizeComparator() {
        return Comparator
                .comparingInt(ProductCatalogService::apparelSizeRank)
                .thenComparing(String.CASE_INSENSITIVE_ORDER);
    }

    private static int apparelSizeRank(String value) {
        if (!present(value)) {
            return Integer.MAX_VALUE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        for (int index = 0; index < APPAREL_SIZE_ORDER.size(); index++) {
            if (APPAREL_SIZE_ORDER.get(index).replace(" ", "").equals(normalized)) {
                return index;
            }
        }
        try {
            return 100 + Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 1000;
        }
    }

    private static String colorKey(String value) {
        return present(value) ? value.trim().toLowerCase(Locale.ROOT) : "__unlabeled__";
    }

    private List<SourceProduct> loadRows(String parentSku) {
        String whereParent = parentSku == null ? "" : " and product.parent_sku = :parentSku ";
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (parentSku != null) {
            parameters.addValue("parentSku", parentSku);
        }
        return jdbc.queryForList("""
                select product.id as id,
                       product.sku,
                       product.cutting_yield,
                       product.launch_date,
                       product.parent_sku,
                       product.product_type,
                       product.product_id,
                       product.product_id_type,
                       product.product_name,
                       product.brand,
                       product.short_description,
                       product.key_features,
                       product.main_image_url,
                       product.secondary_image_urls,
                       product.color,
                       product.color_category,
                       product.size,
                       product.size_group,
                       product.pattern_name,
                       product.material_name,
                       product.gender,
                       product.variant_group_id,
                       product.raw_payload,
                       product.source_updated_at,
                       file.file_name as source_file_name,
                       ir.item_id,
                       ir.upc,
                       ir.wpid,
                       ir.gtin,
                       ir.price,
                       ir.shipping_weight_lb,
                       ir.shipping_weight_unit,
                       ir.shipping_weight_kg,
                       ir.billing_weight_lb,
                       ir.base_fee,
                       ir.apparel_surcharge,
                       ir.low_price_surcharge,
                       ir.delivery_fee,
                       pa.purchase_cost,
                       pa.first_leg,
                       pa.sales_qty,
                       pa.ad_cost,
                       pa.refund_rate
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id
                   and file.active = true
                  left join wm_ads_item_report_record ir
                    on ir.sku = product.sku
                   and ir.current_version = true
                  left join wm_ads_profit_assumption pa
                    on pa.parent_sku = product.parent_sku
                 where product.current_version = true
                """ + whereParent + """
                 order by product.parent_sku, product.sku
                """, parameters).stream().map(this::sourceProduct).toList();
    }

    private SourceProduct sourceProduct(Map<String, Object> row) {
        Map<String, Object> rawPayload = objectMap(row.get("raw_payload"));
        String parentSku = text(row, "parent_sku");
        if (!present(parentSku)) {
            parentSku = parentSku(text(row, "sku"));
        }
        return new SourceProduct(
                longValue(row, "id"),
                text(row, "sku"),
                integerValue(row, "cutting_yield"),
                parentSku,
                text(row, "product_type"),
                text(row, "product_id"),
                text(row, "product_id_type"),
                text(row, "product_name"),
                text(row, "brand"),
                text(row, "short_description"),
                stringList(row.get("key_features")),
                text(row, "main_image_url"),
                stringList(row.get("secondary_image_urls")),
                text(row, "color"),
                text(row, "color_category"),
                text(row, "size"),
                text(row, "size_group"),
                text(row, "pattern_name"),
                text(row, "material_name"),
                text(row, "gender"),
                text(row, "variant_group_id"),
                rawPayload,
                rawText(rawPayload, "swatchImageUrl"),
                truthy(rawPayload.get("isPrimaryVariant")),
                localDateTime(row.get("source_updated_at")),
                text(row, "source_file_name"),
                text(row, "item_id"),
                text(row, "upc"),
                text(row, "wpid"),
                text(row, "gtin"),
                decimal(row, "price"),
                decimal(row, "shipping_weight_lb"),
                text(row, "shipping_weight_unit"),
                decimal(row, "shipping_weight_kg"),
                decimal(row, "billing_weight_lb"),
                decimal(row, "base_fee"),
                decimal(row, "apparel_surcharge"),
                decimal(row, "low_price_surcharge"),
                decimal(row, "delivery_fee"),
                decimal(row, "purchase_cost"),
                decimal(row, "first_leg"),
                decimal(row, "sales_qty"),
                decimal(row, "ad_cost"),
                decimal(row, "refund_rate"),
                localDate(row.get("launch_date"))
        );
    }

    private ProductSummary summary(
            String parentSku,
            List<SourceProduct> rows,
            Map<String, BigDecimal> historyRates,
            ProfitCalculator calculator,
            ParentLifecycleService.LifecycleView lifecycle
    ) {
        SourceProduct representative = representative(rows);
        ProfitView profitView = computeProfit(representative, historyRates, calculator);
        LocalDateTime updatedAt = rows.stream()
                .map(SourceProduct::sourceUpdatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                }).toList());
        List<String> searchTerms = new ArrayList<>();
        rows.forEach(row -> {
            searchTerms.add(row.sku());
            searchTerms.add(row.parentSku());
            searchTerms.add(row.productId());
            searchTerms.add(row.productIdType());
            searchTerms.add(row.productName());
            searchTerms.add(row.brand());
            searchTerms.add(row.productType());
            searchTerms.add(row.color());
            searchTerms.add(row.colorCategory());
            searchTerms.add(row.size());
            searchTerms.add(row.sizeGroup());
            searchTerms.add(row.patternName());
            searchTerms.add(row.materialName());
            searchTerms.add(row.variantGroupId());
            searchTerms.add(row.itemId());
            searchTerms.add(row.upc());
        });
        Integer groupCuttingYield = rows.stream()
                .map(SourceProduct::cuttingYield)
                .filter(value -> value != null)
                .findFirst().orElse(null);
        return new ProductSummary(
                parentSku,
                representative.productName(),
                representative.brand(),
                representative.productType(),
                representative.mainImageUrl(),
                rows.size(),
                distinctCount(rows, SourceProduct::color),
                distinctCount(rows, SourceProduct::size),
                images.size(),
                representative.sourceFileName(),
                updatedAt,
                representative.price(),
                representative.deliveryFee(),
                profitView.profit(),
                profitView.margin(),
                distinctStrings(searchTerms),
                groupCuttingYield,
                lifecycle.clearance(),
                lifecycle.allSeason(),
                lifecycle.seasonType(),
                rows.stream()
                        .map(SourceProduct::launchDate)
                        .filter(date -> date != null)
                        .min(LocalDate::compareTo)
                        .orElse(null)
        );
    }

    private static SourceProduct representative(List<SourceProduct> rows) {
        return rows.stream()
                .max(Comparator
                        .comparingInt(ProductCatalogService::representativeScore)
                        .thenComparing(SourceProduct::sourceUpdatedAt, Comparator.nullsFirst(LocalDateTime::compareTo)))
                .orElseThrow();
    }

    private static int representativeScore(SourceProduct row) {
        int score = row.primaryVariant() ? 100 : 0;
        score += present(row.productName()) ? 20 : 0;
        score += present(row.shortDescription()) ? 20 : 0;
        score += row.keyFeatures().isEmpty() ? 0 : 15;
        score += present(row.mainImageUrl()) ? 15 : 0;
        score += Math.min(10, row.secondaryImageUrls().size());
        return score;
    }

    private static Map<String, String> attributes(SourceProduct row, ProfitView profitView) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "商品类型", row.productType());
        put(attributes, "品牌", row.brand());
        put(attributes, "适用人群", row.gender());
        put(attributes, "材质", row.materialName());
        put(attributes, "图案", row.patternName());
        put(attributes, "尺码组", row.sizeGroup());
        put(attributes, "Item ID", row.itemId());
        put(attributes, "UPC", row.upc());
        put(attributes, "配送重量(LB)", row.shippingWeightLb() == null ? null : row.shippingWeightLb().toPlainString());
        put(attributes, "配送重量(KG)", row.shippingWeightKg() == null ? null : row.shippingWeightKg().toPlainString());
        put(attributes, "预估配送费", row.deliveryFee() == null ? null : "$" + row.deliveryFee().toPlainString());
        put(attributes, "采购成本(¥)", row.purchaseCost() == null ? null : row.purchaseCost().toPlainString());
        put(attributes, "头程(¥)", row.firstLeg() == null ? null : row.firstLeg().toPlainString());
        put(attributes, "销量", row.salesQty() == null ? null : row.salesQty().toPlainString());
        put(attributes, "广告费(原币)", row.adCost() == null ? null : row.adCost().toPlainString());
        put(attributes, "退款率", profitView.refundRate() == null ? null
                : profitView.refundRate().multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) + "%");
        put(attributes, "退款率来源", switch (profitView.refundRateSource()) {
            case "HISTORY" -> "系统历史";
            case "MANUAL" -> "手动";
            default -> "默认 0";
        });
        put(attributes, "测试毛利(¥)", profitView.profit() == null ? null : "¥" + profitView.profit().toPlainString());
        put(attributes, "测试毛利率", profitView.margin() == null ? null
                : profitView.margin().multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) + "%");
        put(attributes, "年龄段", rawText(row.rawPayload(), "ageGroup"));
        put(attributes, "版型", rawText(row.rawPayload(), "clothingFit"));
        put(attributes, "服装风格", rawText(row.rawPayload(), "clothingStyle"));
        put(attributes, "适用场景", rawText(row.rawPayload(), "clothing_occasion"));
        put(attributes, "适用活动", rawText(row.rawPayload(), "activity"));
        put(attributes, "面料结构", rawText(row.rawPayload(), "fabric_construction"));
        put(attributes, "洗护说明", rawText(row.rawPayload(), "fabricCareInstructions"));
        put(attributes, "履约方式", rawText(row.rawPayload(), "thirdPartyProductFulfillmentType"));
        return attributes;
    }

    private static void put(Map<String, String> target, String label, String value) {
        if (present(value)) {
            target.put(label, value.trim());
        }
    }

    private static int distinctCount(List<SourceProduct> rows, Function<SourceProduct, String> getter) {
        return (int) rows.stream()
                .map(getter)
                .filter(ProductCatalogService::present)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .count();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            if (value instanceof String text) {
                if (text.isBlank()) {
                    return List.of();
                }
                if (!text.trim().startsWith("[")) {
                    return List.of(text.trim());
                }
                return objectMapper.readValue(text, STRING_LIST).stream()
                        .filter(ProductCatalogService::present)
                        .map(String::trim)
                        .toList();
            }
            return objectMapper.convertValue(value, STRING_LIST).stream()
                    .filter(ProductCatalogService::present)
                    .map(String::trim)
                    .toList();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return List.of(String.valueOf(value));
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            if (value instanceof String text) {
                return objectMapper.readValue(text, OBJECT_MAP);
            }
            return objectMapper.convertValue(value, OBJECT_MAP);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private static List<String> distinctStrings(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.stream().filter(ProductCatalogService::present).map(String::trim).forEach(result::add);
        return List.copyOf(result);
    }

    private static String rawText(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(ProductCatalogService::present).collect(Collectors.joining(" / "));
        }
        return String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return List.of("true", "yes", "y", "1").contains(normalized);
    }

    private static String parentSku(String sku) {
        if (!present(sku)) {
            return "";
        }
        int separator = sku.indexOf('-');
        return separator > 0 ? sku.substring(0, separator) : sku;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integerValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value).trim());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record CatalogPayload(CatalogMeta meta, List<ProductSummary> products) {
    }

    public record CatalogMeta(
            int parentProductCount,
            int variantCount,
            int sourceFileCount,
            LocalDateTime latestUpdatedAt
    ) {
    }

    public record ProductSummary(
            String parentSku,
            String title,
            String brand,
            String productType,
            String imageUrl,
            int variantCount,
            int colorCount,
            int sizeCount,
            int imageCount,
            String sourceFileName,
            LocalDateTime updatedAt,
            BigDecimal price,
            BigDecimal deliveryFee,
            BigDecimal profit,
            BigDecimal margin,
            List<String> searchTerms,
            Integer cuttingYield,
            boolean clearance,
            boolean allSeason,
            String seasonType,
            LocalDate launchDate
    ) {
    }

    public record ProductDetail(
            String parentSku,
            String title,
            String brand,
            String productType,
            String shortDescription,
            List<String> keyFeatures,
            String mainImageUrl,
            List<String> images,
            int variantCount,
            int colorCount,
            int sizeCount,
            String sourceFileName,
            LocalDateTime updatedAt,
            Map<String, String> attributes,
            Map<String, Object> rawFields,
            List<ColorGroup> colorGroups,
            List<Variant> variants,
            ProfitAssumptionView profitAssumption,
            BigDecimal refundRate,
            String refundRateSource,
            boolean clearance,
            boolean allSeason,
            String seasonType
    ) {
    }

    public record ProfitAssumptionView(
            BigDecimal purchaseCost,
            BigDecimal firstLeg,
            BigDecimal salesQty,
            BigDecimal adCost,
            BigDecimal manualRefundRate
    ) {
    }

    public record SaveProfitAssumptionRequest(
            String parentSku,
            BigDecimal purchaseCost,
            BigDecimal firstLeg,
            BigDecimal salesQty,
            BigDecimal adCost,
            BigDecimal refundRate
    ) {
    }

    public record ColorGroup(
            String color,
            String colorCategory,
            String pattern,
            String mainImageUrl,
            String swatchImageUrl,
            List<String> images,
            List<String> sizes,
            int variantCount,
            boolean primaryColor,
            List<Variant> variants
    ) {
    }

    public record Variant(
            Long id,
            String sku,
            Integer cuttingYield,
            String productId,
            String productIdType,
            String color,
            String colorCategory,
            String size,
            String sizeGroup,
            String pattern,
            String material,
            String imageUrl,
            String swatchImageUrl,
            List<String> images,
            boolean primaryVariant,
            LocalDateTime updatedAt,
            String itemId,
            String upc,
            BigDecimal price,
            BigDecimal shippingWeightLb,
            String shippingWeightUnit,
            BigDecimal shippingWeightKg,
            BigDecimal deliveryFee,
            BigDecimal profit,
            BigDecimal margin,
            BigDecimal refundRate,
            String refundRateSource
    ) {
    }

    private record SourceProduct(
            Long id,
            String sku,
            Integer cuttingYield,
            String parentSku,
            String productType,
            String productId,
            String productIdType,
            String productName,
            String brand,
            String shortDescription,
            List<String> keyFeatures,
            String mainImageUrl,
            List<String> secondaryImageUrls,
            String color,
            String colorCategory,
            String size,
            String sizeGroup,
            String patternName,
            String materialName,
            String gender,
            String variantGroupId,
            Map<String, Object> rawPayload,
            String swatchImageUrl,
            boolean primaryVariant,
            LocalDateTime sourceUpdatedAt,
            String sourceFileName,
            String itemId,
            String upc,
            String wpid,
            String gtin,
            BigDecimal price,
            BigDecimal shippingWeightLb,
            String shippingWeightUnit,
            BigDecimal shippingWeightKg,
            BigDecimal billingWeightLb,
            BigDecimal baseFee,
            BigDecimal apparelSurcharge,
            BigDecimal lowPriceSurcharge,
            BigDecimal deliveryFee,
            BigDecimal purchaseCost,
            BigDecimal firstLeg,
            BigDecimal salesQty,
            BigDecimal adCost,
            BigDecimal manualRefundRate,
            LocalDate launchDate
    ) {
    }

    private record ProfitView(BigDecimal profit, BigDecimal margin, BigDecimal refundRate, String refundRateSource) {
    }

}
