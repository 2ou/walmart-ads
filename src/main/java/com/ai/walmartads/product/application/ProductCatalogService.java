package com.ai.walmartads.product.application;

import com.ai.walmartads.common.WalmartImageUrl;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.inventory.application.ParentLifecycleService;
import com.ai.walmartads.product.domain.ProfitAssumption;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
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
    /**
     * 产品库快照兜底有效期。指纹已覆盖全部数据源，此处仅为防御未纳入指纹的变更路径。
     */
    private static final long CATALOG_CACHE_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    /** 款级划线价的认同阈值：同一链接内该值须占有值变体的 80% 以上才认定为「统一划线价」。 */
    private static final BigDecimal STRIKETHROUGH_CONSENSUS_RATIO = new BigDecimal("0.80");
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

    /**
     * 产品库列表快照。列表接口的整条链路（主查询 + 退款率 + 生命周期 + 28 款 summary + searchTerms 拼装
     * + 序列化）每次都要从头重算，而这是一次「打开就要等」的交互。故按数据指纹缓存整份 payload：
     * 指纹不变直接复用，指纹变化（导入、改裁床数/退款率/配送费、领星回写价格等）或超时即重算。
     * volatile 保证可见性；并发首次请求最多多算一次，结果等价。
     */
    private volatile CatalogSnapshot catalogSnapshot;

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

    /**
     * 产品库列表 + 数据版本。版本（指纹）同时用于 HTTP ETag：
     * 浏览器带 {@code If-None-Match} 且版本未变时可直接 304，省掉整包传输。
     */
    @Transactional(readOnly = true)
    public CatalogResult loadWithVersion() {
        String fingerprint = fingerprint();
        long now = System.nanoTime();
        CatalogSnapshot current = catalogSnapshot;
        if (current != null
                && current.fingerprint().equals(fingerprint)
                && current.expiresAtNanos() > now) {
            return new CatalogResult(fingerprint, current.payload());
        }
        CatalogPayload payload = computeCatalog();
        catalogSnapshot = new CatalogSnapshot(fingerprint, payload, now + CATALOG_CACHE_TTL_NANOS);
        return new CatalogResult(fingerprint, payload);
    }

    @Transactional(readOnly = true)
    public CatalogPayload load() {
        return loadWithVersion().payload();
    }

    /**
     * 依赖数据的「廉价指纹」（实测约 15ms），覆盖产品库与详情用到的全部数据源：
     * 产品记录（含生效版本数）、导入文件（含 active 生效数）、SKU 映射、订单与退货明细、
     * 利润假设、款生命周期、裁床数手工设置。任一规模或生效状态/更新时间变化都会改变指纹。
     */
    private String fingerprint() {
        return jdbc.queryForObject("""
                select concat_ws('|',
                           (select coalesce(max(id), 0) from wm_ads_product_record),
                           (select count(*) from wm_ads_product_record where current_version = true),
                           (select coalesce(max(id), 0) from wm_ads_import_file),
                           (select coalesce(sum(case when active then 1 else 0 end), 0) from wm_ads_import_file),
                           (select count(*) from wm_ads_sku_mapping),
                           (select count(*) from wm_ads_sku_mapping where current_version = true),
                           (select coalesce(max(id), 0) from wm_ads_order_record),
                           (select count(*) from wm_ads_order_record),
                           (select coalesce(max(id), 0) from wm_ads_return_order),
                           (select count(*) from wm_ads_return_order),
                           (select count(*) from wm_ads_profit_assumption),
                           (select coalesce(date_format(max(updated_at), '%Y%m%d%H%i%s%f'), 'x') from wm_ads_profit_assumption),
                           (select count(*) from wm_ads_parent_lifecycle),
                           (select coalesce(date_format(max(updated_at), '%Y%m%d%H%i%s%f'), 'x') from wm_ads_parent_lifecycle),
                           (select count(*) from wm_ads_product_manual_setting),
                           (select coalesce(date_format(max(updated_at), '%Y%m%d%H%i%s%f'), 'x') from wm_ads_product_manual_setting)
                       )
                """, new MapSqlParameterSource(), String.class);
    }

    /** 供数据变更路径显式失效（非必需 —— 指纹机制已覆盖）。 */
    public void invalidate() {
        catalogSnapshot = null;
    }

    /** 单个父款详情的版本：全局指纹 + 父款号，父款被删/数据变更都会改变它。 */
    @Transactional(readOnly = true)
    public String detailVersion(String parentSku) {
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        return fingerprint() + "|" + safeParentSku;
    }

    /**
     * 详情抽屉里「查看上传表原始字段」按需拉取（折叠块展开时才请求）。
     * 该字段是完整上传 payload（59+ 键），此前每次都随详情一起下发却几乎没人展开，属纯浪费。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> rawFields(String parentSku) {
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        List<SourceProduct> rows = loadRows(safeParentSku, true);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("产品库中找不到父 SKU：" + safeParentSku);
        }
        return representative(rows).rawPayload();
    }

    private CatalogPayload computeCatalog() {
        // 列表页不需要完整 raw_payload（见 loadRows 注释），按需精简，避免每次打开都搬 4MB JSON。
        List<SourceProduct> rows = loadRows(null, false);
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
        List<SourceProduct> rows = loadRows(safeParentSku, true);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("产品库中找不到父 SKU：" + safeParentSku);
        }
        Map<String, BigDecimal> historyRates = refundRateService.ratesByParent();
        ParentLifecycleService.LifecycleView lifecycle = lifecycleService.viewFor(safeParentSku);
        SourceProduct representative = representative(rows);
        BigDecimal effectiveDeliveryFee = representative.manualDeliveryFee() != null ? representative.manualDeliveryFee() : representative.deliveryFee();
        ProfitView representativeProfit = computeProfit(representative, historyRates, profitCalculator, effectiveDeliveryFee);
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
                representative.manualRefundRate(),
                representative.preferManualRefundRate(),
                representative.manualDeliveryFee()
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
                representativeProfit.historicalRefundRate(),
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
        persistCuttingYield(record.getParentSku(), cuttingYield);
    }

    @Transactional
    public void updateCuttingYieldByParent(String parentSku, Integer cuttingYield) {
        validateCuttingYield(cuttingYield);
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        persistCuttingYield(safeParentSku, cuttingYield);
    }

    private void persistCuttingYield(String parentSku, Integer cuttingYield) {
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
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("workspaceId", WorkspaceContext.requireId())
                .addValue("parentSku", safeParentSku)
                .addValue("cuttingYield", cuttingYield);
        if (cuttingYield == null) {
            jdbc.update("""
                    delete from wm_ads_product_manual_setting
                     where workspace_id=:workspaceId and parent_sku=:parentSku
                    """, parameters);
        } else {
            jdbc.update("""
                    insert into wm_ads_product_manual_setting
                        (workspace_id, parent_sku, cutting_yield)
                    values (:workspaceId, :parentSku, :cuttingYield)
                    on duplicate key update cutting_yield=values(cutting_yield), updated_at=now(6)
                    """, parameters);
        }
    }

    /**
     * 只更新整款级手工预估退款率（null 表示清除）。
     * 独立方法的原因：saveProfitAssumption 对 deliveryFee 采用「直接覆盖」语义（允许清空），
     * 若走通用保存接口只传 refundRate，会把该款手工配送费连带清空。
     */
    @Transactional
    public void updateRefundRate(String parentSku, BigDecimal refundRate) {
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        if (refundRate != null
                && (refundRate.compareTo(BigDecimal.ZERO) < 0 || refundRate.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("退款率需在 0 ~ 1 之间（小数）。");
        }
        ProfitAssumption entity = profitAssumptionRepository.findById(safeParentSku)
                .orElseGet(() -> new ProfitAssumption(safeParentSku));
        entity.setRefundRate(refundRate);
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        profitAssumptionRepository.save(entity);
    }

    private static void validateCuttingYield(Integer cuttingYield) {
        if (cuttingYield == null) {
            return;
        }
        if (cuttingYield < 0) {
            throw new IllegalArgumentException("裁床数必须是非负整数；0 表示淘汰款，不维护时请留空。");
        }
        if (cuttingYield > 0 && cuttingYield % 5 != 0) {
            throw new IllegalArgumentException("裁床数必须是 5 的倍数，才能保证每个 SKU 的做货数量均为 5 的倍数。");
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
                  from wm_ads_order_record_valid o
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
        if (request.purchaseCost() != null) entity.setPurchaseCost(request.purchaseCost());
        if (request.firstLeg() != null) entity.setFirstLeg(request.firstLeg());
        if (request.salesQty() != null) entity.setSalesQty(request.salesQty());
        if (request.adCost() != null) entity.setAdCost(request.adCost());
        if (request.refundRate() != null) entity.setRefundRate(request.refundRate());
        if (request.preferManualRefundRate() != null) {
            entity.setPreferManualRefundRate(request.preferManualRefundRate());
        }
        entity.setDeliveryFee(request.deliveryFee());
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
                entity.getRefundRate(),
                entity.isPreferManualRefundRate(),
                entity.getDeliveryFee()
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
        ProfitView profitView = computeProfit(row, historyRates, calculator, null);
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
                row.strikethroughPrice(),
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

    private static ProfitView computeProfit(SourceProduct row, Map<String, BigDecimal> historyRates, ProfitCalculator calculator, BigDecimal deliveryFeeOverride) {
        BigDecimal history = historyRates.get(row.parentSku());
        BigDecimal manual = row.manualRefundRate();
        BigDecimal refundRate;
        String source;
        if (row.preferManualRefundRate() && manual != null) {
            refundRate = manual;
            source = "MANUAL";
        } else if (history != null) {
            refundRate = history;
            source = "CUMULATIVE";
        } else if (manual != null) {
            refundRate = manual;
            source = "MANUAL_FALLBACK";
        } else {
            refundRate = BigDecimal.ZERO;
            source = "DEFAULT";
        }
        BigDecimal deliveryFee = deliveryFeeOverride != null ? deliveryFeeOverride : row.deliveryFee();
        ProfitCalculator.ProfitResult result = calculator.compute(
                row.price(),
                deliveryFee,
                row.purchaseCost(),
                row.firstLeg(),
                row.salesQty(),
                row.adCost(),
                refundRate,
                EXCHANGE_RATE
        );
        if (result == null) {
            return new ProfitView(null, null, refundRate, source, history);
        }
        return new ProfitView(result.profit(), result.margin(), refundRate, source, history);
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

    /**
     * 读取产品记录。
     *
     * <p><b>为什么要按需取 raw_payload</b>：该列是完整的上传 payload（全库 current_version=1 合计 <b>4MB</b>），
     * 列表接口只用到它里面的 {@code swatchImageUrl} 与 {@code isPrimaryVariant} 两个键，
     * 却要为此搬运 4MB 并在 Java 侧逐行 Jackson 解析。实测该列让列表主查询从 <b>37ms 涨到 300ms</b>。
     * 故 {@code withRawPayload=false}（列表页）时改用 {@code JSON_EXTRACT} 只取这两个键；
     * {@code true}（详情抽屉）时才取整列 —— 详情页需要回传完整 raw 字段并据此拼属性。
     */
    private List<SourceProduct> loadRows(String parentSku, boolean withRawPayload) {
        String whereParent = parentSku == null ? "" : " and product.parent_sku = :parentSku ";
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (parentSku != null) {
            parameters.addValue("parentSku", parentSku);
        }
        String rawPayloadColumns = withRawPayload
                ? "product.raw_payload,"
                : """
                  json_unquote(json_extract(product.raw_payload, '$.swatchImageUrl')) as swatch_image_url,
                          json_unquote(json_extract(product.raw_payload, '$.isPrimaryVariant')) as is_primary_variant,""";
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
                       %s
                       product.source_updated_at,
                       file.file_name as source_file_name,
                       product.price,
                       product.strikethrough_price,
                       product.upc,
                       product.gtin,
                       product.listing_status,
                       product.store,
                       product.delivery_fee,
                       pa.purchase_cost,
                       pa.first_leg,
                       pa.sales_qty,
                       pa.ad_cost,
                       pa.refund_rate,
                       pa.prefer_manual_refund_rate,
                       pa.delivery_fee as manual_delivery_fee
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id
                   and file.active = true
                  left join wm_ads_profit_assumption pa
                    on pa.parent_sku = product.parent_sku
                 where product.current_version = true
                """.formatted(rawPayloadColumns) + whereParent + """
                 order by product.parent_sku, product.sku
                """, parameters).stream().map(this::sourceProduct).toList();
    }

    private SourceProduct sourceProduct(Map<String, Object> row) {
        // 列表页走精简列（swatch_image_url / is_primary_variant），详情页走完整 raw_payload。
        boolean slim = !row.containsKey("raw_payload");
        Map<String, Object> rawPayload = slim ? Map.of() : objectMap(row.get("raw_payload"));
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
                slim ? text(row, "swatch_image_url") : rawText(rawPayload, "swatchImageUrl"),
                slim ? truthy(row.get("is_primary_variant")) : truthy(rawPayload.get("isPrimaryVariant")),
                localDateTime(row.get("source_updated_at")),
                text(row, "source_file_name"),
                null,
                text(row, "upc"),
                null,
                text(row, "gtin"),
                decimal(row, "price"),
                decimal(row, "strikethrough_price"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                decimal(row, "delivery_fee"),
                decimal(row, "purchase_cost"),
                decimal(row, "first_leg"),
                decimal(row, "sales_qty"),
                decimal(row, "ad_cost"),
                decimal(row, "refund_rate"),
                truthy(row.get("prefer_manual_refund_rate")),
                decimal(row, "manual_delivery_fee"),
                text(row, "listing_status"),
                text(row, "store"),
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
        ProfitView profitView = computeProfit(representative, historyRates, calculator, representative.manualDeliveryFee() != null ? representative.manualDeliveryFee() : representative.deliveryFee());
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
        // 与卡片顶层字段（parentSku / title / brand / productType）完全重复的检索项不再下发：
        // 前端 fuzzySearchMatches 已把这些字段作为独立入参，重复传一遍不改变任何检索结果，纯属体积浪费。
        Set<String> topLevelTerms = new LinkedHashSet<>();
        for (String candidate : List.of(
                parentSku == null ? "" : parentSku,
                representative.productName() == null ? "" : representative.productName(),
                representative.brand() == null ? "" : representative.brand(),
                representative.productType() == null ? "" : representative.productType()
        )) {
            if (present(candidate)) {
                topLevelTerms.add(candidate.trim().toLowerCase(Locale.ROOT));
            }
        }
        List<String> trimmedSearchTerms = distinctStrings(searchTerms).stream()
                .filter(term -> !topLevelTerms.contains(term.trim().toLowerCase(Locale.ROOT)))
                .toList();
        Integer groupCuttingYield = rows.stream()
                .map(SourceProduct::cuttingYield)
                .filter(value -> value != null)
                .findFirst().orElse(null);
        BigDecimal strikethroughPrice = consensusStrikethroughPrice(rows);
        return new ProductSummary(
                parentSku,
                representative.productName(),
                representative.brand(),
                representative.productType(),
                WalmartImageUrl.thumb(representative.mainImageUrl()),
                rows.size(),
                distinctCount(rows, SourceProduct::color),
                distinctCount(rows, SourceProduct::size),
                images.size(),
                representative.sourceFileName(),
                updatedAt,
                representative.price(),
                strikethroughPrice,
                representative.manualDeliveryFee() != null ? representative.manualDeliveryFee() : representative.deliveryFee(),
                profitView.profit(),
                profitView.margin(),
                representative.manualRefundRate(),
                profitView.historicalRefundRate(),
                profitView.refundRateSource(),
                trimmedSearchTerms,
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

    /**
     * 款级划线价 —— 「整条链接统一一个值，才叫划线价」，其余一律不算。
     *
     * <p>规则：取链接内出现次数最多的划线价，且必须同时满足
     * <ol>
     *   <li>该值在**有值变体**中的占比 ≥ {@link #STRIKETHROUGH_CONSENSUS_RATIO}；</li>
     *   <li>该值**严格高于**款级售价。</li>
     * </ol>
     * 任一不满足即返回 {@code null}，由前端显示「—」。
     *
     * <p>为什么必须收敛：实测该字段在导入环节被污染 —— 多款存在「划线价 = 售价」甚至
     * 「划线价 &lt; 售价」的行（PNK363127 29/31、PNK366066 51/52、PNK366078 12/12），
     * 且个别链接的变体值分裂成多档（PNK369006 众数仅占 38%）。这些都不构成真实划线价，
     * 直接展示会误导定价判断。此前的实现是「抓代表变体、没有就取第一个非空」，
     * 从不校验链接是否统一，脏值排在前面就会直接显示出来。
     *
     * <p>只在展示层收敛：DB 原始值原样保留，口径要调整无需重新同步。
     * 变体级明细（{@link #toVariant}）仍照显真值，便于定位哪些变体是脏的。
     */
    private static BigDecimal consensusStrikethroughPrice(List<SourceProduct> rows) {
        Map<BigDecimal, Long> counts = rows.stream()
                .map(SourceProduct::strikethroughPrice)
                .filter(value -> value != null)
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        BigDecimal highestPrice = rows.stream()
                .map(SourceProduct::price)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
        if (counts.isEmpty() || highestPrice == null) {
            return null;
        }
        long filled = counts.values().stream().mapToLong(Long::longValue).sum();
        Map.Entry<BigDecimal, Long> mode = counts.entrySet().stream()
                .max(Comparator.<Map.Entry<BigDecimal, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow();
        BigDecimal consensus = BigDecimal.valueOf(mode.getValue())
                .divide(BigDecimal.valueOf(filled), 4, RoundingMode.HALF_UP);
        if (consensus.compareTo(STRIKETHROUGH_CONSENSUS_RATIO) < 0) {
            return null;
        }
        BigDecimal modeValue = mode.getKey();
        if (modeValue.compareTo(highestPrice) <= 0) {
            return null;
        }
        return modeValue;
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
        put(attributes, "售价", row.price() == null ? null : "$" + row.price().toPlainString());
        put(attributes, "划线价", row.strikethroughPrice() == null ? null : "$" + row.strikethroughPrice().toPlainString());
        put(attributes, "商品类型", row.productType());
        put(attributes, "品牌", row.brand());
        put(attributes, "店铺", row.store());
        put(attributes, "上架状态", row.listingStatus());
        put(attributes, "适用人群", row.gender());
        put(attributes, "材质", row.materialName());
        put(attributes, "图案", row.patternName());
        put(attributes, "尺码组", row.sizeGroup());
        put(attributes, "Item ID", row.itemId());
        put(attributes, "UPC", row.upc());
        put(attributes, "配送重量(LB)", row.shippingWeightLb() == null ? null : row.shippingWeightLb().toPlainString());
        put(attributes, "配送重量(KG)", row.shippingWeightKg() == null ? null : row.shippingWeightKg().toPlainString());
        BigDecimal displayDeliveryFee = row.manualDeliveryFee() != null ? row.manualDeliveryFee() : row.deliveryFee();
        put(attributes, "预估配送费", displayDeliveryFee == null ? null : "$" + displayDeliveryFee.toPlainString());
        put(attributes, "采购成本(¥)", row.purchaseCost() == null ? null : row.purchaseCost().toPlainString());
        put(attributes, "头程(¥)", row.firstLeg() == null ? null : row.firstLeg().toPlainString());
        put(attributes, "销量", row.salesQty() == null ? null : row.salesQty().toPlainString());
        put(attributes, "广告费(原币)", row.adCost() == null ? null : row.adCost().toPlainString());
        put(attributes, "退款率", profitView.refundRate() == null ? null
                : profitView.refundRate().multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP) + "%");
        put(attributes, "退款率来源", refundRateSourceLabel(profitView.refundRateSource()));
        put(attributes, "款号累计总退款率", profitView.historicalRefundRate() == null ? null
                : profitView.historicalRefundRate().multiply(new BigDecimal("100"))
                .setScale(2, java.math.RoundingMode.HALF_UP) + "%");
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

    private static String refundRateSourceLabel(String source) {
        return switch (source) {
            case "CUMULATIVE" -> "款号累计总退款率（默认）";
            case "MANUAL" -> "手工退款率（优先）";
            case "MANUAL_FALLBACK" -> "手工退款率（无累计数据）";
            default -> "无累计或手工值，按 0";
        };
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

    /** 列表结果 + 其对应的数据版本（指纹），供控制器生成 ETag。 */
    public record CatalogResult(String version, CatalogPayload payload) {
    }

    private record CatalogSnapshot(String fingerprint, CatalogPayload payload, long expiresAtNanos) {
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
            BigDecimal strikethroughPrice,
            BigDecimal deliveryFee,
            BigDecimal profit,
            BigDecimal margin,
            BigDecimal manualRefundRate,
            BigDecimal historicalRefundRate,
            String refundRateSource,
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
            /**
             * 款级图片全量清单。前端详情的主图/图集统一取自 {@code colorGroups[].images}，
             * 该字段无人消费 —— 出参忽略，省掉每次约 20KB。
             */
            @JsonIgnore List<String> images,
            int variantCount,
            int colorCount,
            int sizeCount,
            String sourceFileName,
            LocalDateTime updatedAt,
            Map<String, String> attributes,
            /**
             * 上传表原始字段，改为前端折叠块展开时走 {@code GET /api/products/{parentSku}/raw-fields} 按需拉取。
             */
            @JsonIgnore Map<String, Object> rawFields,
            List<ColorGroup> colorGroups,
            /**
             * 变体明细的唯一权威副本在 {@code colorGroups[].variants}（前端表格就是按颜色组渲染的）。
             * 顶层再传一份完全相同的数据会让详情包体积翻倍（80 变体款实测 141KB），故出参忽略。
             */
            @JsonIgnore List<Variant> variants,
            ProfitAssumptionView profitAssumption,
            BigDecimal refundRate,
            String refundRateSource,
            BigDecimal historicalRefundRate,
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
            BigDecimal manualRefundRate,
            boolean preferManualRefundRate,
            BigDecimal deliveryFee
    ) {
    }

    public record SaveProfitAssumptionRequest(
            String parentSku,
            BigDecimal purchaseCost,
            BigDecimal firstLeg,
            BigDecimal salesQty,
            BigDecimal adCost,
            BigDecimal refundRate,
            Boolean preferManualRefundRate,
            BigDecimal deliveryFee
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
            /** 每个变体都重复携带同一组图集；图集只走 {@code colorGroups[].images}，此处忽略（80 变体款省约 71KB）。 */
            @JsonIgnore List<String> images,
            boolean primaryVariant,
            LocalDateTime updatedAt,
            String itemId,
            String upc,
            BigDecimal price,
            BigDecimal strikethroughPrice,
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
            BigDecimal strikethroughPrice,
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
            boolean preferManualRefundRate,
            BigDecimal manualDeliveryFee,
            String listingStatus,
            String store,
            LocalDate launchDate
    ) {
    }

    private record ProfitView(
            BigDecimal profit,
            BigDecimal margin,
            BigDecimal refundRate,
            String refundRateSource,
            BigDecimal historicalRefundRate
    ) {
    }

}
