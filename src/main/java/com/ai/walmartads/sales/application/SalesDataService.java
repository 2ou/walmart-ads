package com.ai.walmartads.sales.application;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.common.WalmartImageUrl;
import com.ai.walmartads.inventory.application.TransitInventoryService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class SalesDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransitInventoryService transitInventoryService;

    public SalesDataService(NamedParameterJdbcTemplate jdbc, TransitInventoryService transitInventoryService) {
        this.jdbc = jdbc;
        this.transitInventoryService = transitInventoryService;
    }

    public SalesPayload load(int requestedDays) {
        return load(requestedDays, null, null);
    }

    public SalesPayload load(int requestedDays, LocalDate requestedStart, LocalDate requestedEnd) {
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new IllegalArgumentException("自定义时间范围必须同时提供开始日期和结束日期。");
        }
        int days = Math.max(1, Math.min(requestedDays, 365));
        Map<String, Object> bounds = jdbc.queryForMap("""
                select min(orders.order_date) as first_date,
                       max(orders.order_date) as latest_date
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id and file.active = true
                """, new MapSqlParameterSource());
        LocalDate firstDate = localDate(bounds.get("first_date"));
        LocalDate latestDate = localDate(bounds.get("latest_date"));
        latestDate = UsBusinessDate.capToVisibleSalesDate(latestDate);

        LocalDate currentStart = latestDate.minusDays(days - 1L);
        LocalDate currentEnd = latestDate;
        if (requestedStart != null) {
            if (requestedStart.isAfter(requestedEnd)) {
                throw new IllegalArgumentException("开始日期不能晚于结束日期。");
            }
            currentStart = requestedStart;
            currentEnd = UsBusinessDate.capToVisibleSalesDate(requestedEnd);
            if (currentStart.isAfter(currentEnd)) {
                throw new IllegalArgumentException("当前可查看的最新销售日期为 " + currentEnd
                        + "（北京时间 16:00 后开放当日）。");
            }
            days = Math.toIntExact(ChronoUnit.DAYS.between(currentStart, currentEnd) + 1);
        }
        int analysisDays = days;
        LocalDate previousEnd = currentStart.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(analysisDays - 1L);
        MapSqlParameterSource window = new MapSqlParameterSource()
                .addValue("currentStart", currentStart)
                .addValue("latestDate", currentEnd)
                .addValue("previousStart", previousStart)
                .addValue("previousEnd", previousEnd);

        List<DailyRow> daily = loadDaily(currentStart, currentEnd);
        // 折线图最少展示 7 天：以选中周期结束日为终点，向前取 max(7, 选中跨度) 天；与 daily 解耦，不污染 KPI
        int trendDays = Math.max(7, days);
        LocalDate trendStart = currentEnd.minusDays(trendDays - 1L);
        List<DailyRow> trendDaily = loadDaily(trendStart, currentEnd);
        Map<String, SalesAggregate> sales = loadSkuSales(window);
        Map<String, ProductInfo> products = loadProducts();
        InventorySnapshotData inventorySnapshot = loadLatestInventory();
        Map<String, InventoryInfo> inventory = inventorySnapshot.rows();

        // 有效款口径：以产品库（current_version=true）为准。产品库非空时，库存/订单集
        // 里不属于产品库的 SKU 一律剔除（它们会经 buildSkuRow 兜底成“款”显示）；
        // 产品库为空时兜底放行全部，避免误杀成空页。
        Set<String> skuUniverse = new LinkedHashSet<>();
        skuUniverse.addAll(products.keySet());
        if (products.isEmpty()) {
            skuUniverse.addAll(inventory.keySet());
            skuUniverse.addAll(sales.keySet());
        }

        double totalUnits = daily.stream().mapToDouble(DailyRow::units).sum();
        double totalRevenue = daily.stream().mapToDouble(DailyRow::revenue).sum();
        List<SkuRow> skuRows = skuUniverse.stream()
                .map(sku -> buildSkuRow(
                        sku,
                        sales.getOrDefault(sku, SalesAggregate.empty(sku)),
                        products.get(sku),
                        inventory.get(sku),
                        analysisDays,
                        totalUnits
                ))
                .sorted(Comparator.comparingDouble(SkuRow::units).reversed()
                        .thenComparing(Comparator.comparingDouble(SkuRow::revenue).reversed())
                        .thenComparing(SkuRow::sku))
                .toList();
        List<ParentRow> parentRows = buildParentRows(skuRows, analysisDays, totalUnits);

        int sampleDays = (int) daily.stream().filter(row -> row.units() != 0 || row.orders() != 0).count();
        int activeSkus = (int) skuRows.stream().filter(row -> row.units() > 0).count();
        int previousActiveSkus = (int) skuRows.stream().filter(row -> row.previousUnits() > 0).count();
        int activeParents = (int) parentRows.stream().filter(row -> row.units() > 0).count();
        double previousUnits = skuRows.stream().mapToDouble(SkuRow::previousUnits).sum();
        double previousRevenue = skuRows.stream().mapToDouble(SkuRow::previousRevenue).sum();
        long currentOrders = daily.stream().mapToLong(DailyRow::orders).sum();
        long previousOrders = sales.values().stream().mapToLong(SalesAggregate::previousOrders).sum();
        double averagePrice = divide(totalRevenue, totalUnits);
        double previousAveragePrice = divide(previousRevenue, previousUnits);

        Summary summary = new Summary(
                round(totalUnits),
                round(totalRevenue),
                currentOrders,
                round(totalUnits / analysisDays),
                round(totalRevenue / analysisDays),
                round(averagePrice),
                round(divide(totalRevenue, currentOrders)),
                activeSkus,
                activeParents,
                skuUniverse.size(),
                round(divide(activeSkus, skuUniverse.size())),
                change(totalUnits, previousUnits),
                change(totalRevenue, previousRevenue),
                change(currentOrders, previousOrders),
                change(activeSkus, previousActiveSkus),
                change(averagePrice, previousAveragePrice)
        );
        Meta meta = new Meta(
                firstDate,
                latestDate,
                currentStart,
                currentEnd,
                previousStart,
                previousEnd,
                analysisDays,
                sampleDays,
                "USD",
                "订单表 Item Cost",
                "订单表 Order Date",
                inventorySnapshot.snapshotAt()
        );
        return new SalesPayload(
                meta,
                summary,
                daily,
                trendDaily,
                parentRows,
                skuRows,
                buildDimensions(skuRows, true, totalUnits),
                buildDimensions(skuRows, false, totalUnits)
        );
    }

    private List<DailyRow> loadDaily(LocalDate start, LocalDate end) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end);
        Map<LocalDate, double[]> salesValues = new HashMap<>();
        jdbc.queryForList("""
                select orders.order_date as sales_date,
                       sum(orders.quantity) as units,
                       sum(orders.quantity * orders.item_cost) as revenue,
                       count(distinct orders.order_number) as order_count
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id and file.active = true
                 where orders.order_date between :start and :end
                 group by orders.order_date
                 order by orders.order_date
                """, params).forEach(row -> {
            LocalDate date = localDate(row.get("sales_date"));
            salesValues.put(date, new double[]{
                    number(row, "units"),
                    number(row, "revenue"),
                    longNumber(row, "order_count")
            });
        });
        // 广告费 / 广告归因销售额按日汇总（领星 AD_ITEM 日事实），供销量趋势悬停展示
        Map<LocalDate, double[]> adValues = new HashMap<>();
        jdbc.queryForList("""
                select ads.report_date as ad_date,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report ads
                 where ads.report_level = 'AD_ITEM'
                   and ads.report_date between :start and :end
                 group by ads.report_date
                """, params).forEach(row -> {
            LocalDate date = localDate(row.get("ad_date"));
            adValues.put(date, new double[]{number(row, "spend"), number(row, "ad_sales")});
        });
        List<DailyRow> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            double[] sales = salesValues.get(date);
            double units = sales == null ? 0 : round(sales[0]);
            double revenue = sales == null ? 0 : round(sales[1]);
            long orderCount = sales == null ? 0L : (long) sales[2];
            double[] ads = adValues.get(date);
            double adSpend = ads == null ? 0 : round(ads[0]);
            double adSales = ads == null ? 0 : round(ads[1]);
            result.add(new DailyRow(
                    date,
                    units,
                    revenue,
                    orderCount,
                    round(divide(revenue, units)),
                    adSpend,
                    round(divide(adSales, adSpend))
            ));
        }
        return result;
    }

    private Map<String, SalesAggregate> loadSkuSales(MapSqlParameterSource window) {
        Map<String, SalesAggregate> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku as sku,
                       max(orders.parent_sku) as parent_sku,
                       max(orders.item_description) as item_name,
                       sum(case when orders.order_date between :currentStart and :latestDate
                                then orders.quantity else 0 end) as current_units,
                       sum(case when orders.order_date between :currentStart and :latestDate
                                then orders.quantity * orders.item_cost else 0 end) as current_revenue,
                       count(distinct case when orders.order_date between :currentStart and :latestDate
                                           then orders.order_number else null end) as current_orders,
                       sum(case when orders.order_date between :previousStart and :previousEnd
                                then orders.quantity else 0 end) as previous_units,
                       sum(case when orders.order_date between :previousStart and :previousEnd
                                then orders.quantity * orders.item_cost else 0 end) as previous_revenue,
                       count(distinct case when orders.order_date between :previousStart and :previousEnd
                                           then orders.order_number else null end) as previous_orders
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id and file.active = true
                 where orders.order_date between :previousStart and :latestDate
                 group by orders.sku
                """, window).forEach(row -> {
            String sku = text(row, "sku");
            result.put(sku, new SalesAggregate(
                    sku,
                    text(row, "parent_sku"),
                    text(row, "item_name"),
                    number(row, "current_units"),
                    number(row, "current_revenue"),
                    longNumber(row, "current_orders"),
                    number(row, "previous_units"),
                    number(row, "previous_revenue"),
                    longNumber(row, "previous_orders")
            ));
        });
        return result;
    }

    private Map<String, ProductInfo> loadProducts() {
        Map<String, ProductInfo> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select product.sku as sku,
                       max(product.parent_sku) as parent_sku,
                       max(product.product_name) as product_name,
                       max(product.main_image_url) as image_url,
                       max(product.color) as color,
                       max(product.size) as size
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id and file.active = true
                 where product.current_version = true
                 group by product.sku
                """, new MapSqlParameterSource()).forEach(row -> {
            String sku = text(row, "sku");
            result.put(sku, new ProductInfo(
                    sku,
                    text(row, "parent_sku"),
                    text(row, "product_name"),
                    WalmartImageUrl.thumb(text(row, "image_url")),
                    text(row, "color"),
                    text(row, "size")
            ));
        });
        return result;
    }

    private InventorySnapshotData loadLatestInventory() {
        Map<String, Object> bounds = jdbc.queryForMap("""
                select max(inventory.snapshot_at) as snapshot_at
                  from wm_ads_inventory_unified inventory
                """, new MapSqlParameterSource());
        LocalDateTime latestSnapshot = localDateTime(bounds.get("snapshot_at"));
        if (latestSnapshot == null) {
            return new InventorySnapshotData(null, Map.of());
        }
        Map<String, Double> transitBySku = transitInventoryService.load().unitsBySku();
        Map<String, InventoryInfo> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select inventory.sku as sku,
                       max(inventory.parent_sku) as parent_sku,
                       max(inventory.item_id) as item_id,
                       max(inventory.item_name) as item_name,
                       sum(inventory.available_units) as available_units
                  from wm_ads_inventory_unified inventory
                 where inventory.snapshot_at = :snapshotAt
                 group by inventory.sku
                """, new MapSqlParameterSource("snapshotAt", latestSnapshot)).forEach(row -> {
            String sku = text(row, "sku");
            result.put(sku, new InventoryInfo(
                    sku,
                    text(row, "parent_sku"),
                    text(row, "item_id"),
                    text(row, "item_name"),
                    number(row, "available_units"),
                    0,
                    transitBySku.getOrDefault(sku, 0.0)
            ));
        });
        return new InventorySnapshotData(latestSnapshot, result);
    }

    private static SkuRow buildSkuRow(
            String sku,
            SalesAggregate sales,
            ProductInfo product,
            InventoryInfo inventory,
            int days,
            double totalUnits
    ) {
        String parentSku = firstText(
                product == null ? null : product.parentSku(),
                sales.parentSku(),
                inventory == null ? null : inventory.parentSku(),
                sku
        );
        String itemName = firstText(
                product == null ? null : product.productName(),
                sales.itemName(),
                inventory == null ? null : inventory.itemName(),
                sku
        );
        double available = inventory == null ? 0 : inventory.availableUnits();
        double dailyUnits = sales.currentUnits() / days;
        return new SkuRow(
                sku,
                parentSku,
                inventory == null ? null : inventory.itemId(),
                itemName,
                product == null ? null : product.imageUrl(),
                product == null ? null : product.color(),
                product == null ? null : product.size(),
                round(sales.currentUnits()),
                round(sales.currentRevenue()),
                sales.currentOrders(),
                round(sales.previousUnits()),
                round(sales.previousRevenue()),
                change(sales.currentUnits(), sales.previousUnits()),
                change(sales.currentRevenue(), sales.previousRevenue()),
                round(divide(sales.currentRevenue(), sales.currentUnits())),
                round(dailyUnits),
                round(divide(sales.currentUnits(), totalUnits)),
                round(available),
                round(inventory == null ? 0 : inventory.reservedUnits()),
                round(inventory == null ? 0 : inventory.transitUnits()),
                dailyUnits > 0 ? round(available / dailyUnits) : null,
                status(sales.currentUnits(), sales.previousUnits())
        );
    }

    private static List<ParentRow> buildParentRows(List<SkuRow> skuRows, int days, double totalUnits) {
        Map<String, ParentAccumulator> groups = new LinkedHashMap<>();
        skuRows.forEach(row -> groups.computeIfAbsent(row.parentSku(), ParentAccumulator::new).add(row));
        return groups.values().stream()
                .map(group -> group.toRow(days, totalUnits))
                .sorted(Comparator.comparingDouble(ParentRow::units).reversed()
                        .thenComparing(Comparator.comparingDouble(ParentRow::revenue).reversed())
                        .thenComparing(ParentRow::parentSku))
                .toList();
    }

    private static List<DimensionRow> buildDimensions(List<SkuRow> rows, boolean color, double totalUnits) {
        Map<String, DimensionAccumulator> groups = new LinkedHashMap<>();
        rows.forEach(row -> {
            String label = color ? row.color() : row.size();
            label = label == null || label.isBlank() ? "未填写" : label.trim();
            groups.computeIfAbsent(label, DimensionAccumulator::new).add(row);
        });
        return groups.values().stream()
                .map(group -> group.toRow(totalUnits))
                .sorted(Comparator.comparingDouble(DimensionRow::units).reversed()
                        .thenComparing(DimensionRow::label))
                .limit(12)
                .toList();
    }

    private static String status(double current, double previous) {
        if (current <= 0) {
            return "NO_SALES";
        }
        if (previous <= 0) {
            return "NEW";
        }
        double change = (current - previous) / Math.abs(previous);
        if (change >= 0.20) {
            return "GROWING";
        }
        if (change <= -0.20) {
            return "DECLINING";
        }
        return "STABLE";
    }

    private static Double change(double current, double previous) {
        if (Math.abs(previous) < 0.000001) {
            return Math.abs(current) < 0.000001 ? 0D : null;
        }
        return round((current - previous) / Math.abs(previous));
    }

    private static double divide(double numerator, double denominator) {
        return Math.abs(denominator) < 0.000001 ? 0 : numerator / denominator;
    }

    private static double divide(double numerator, long denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return new BigDecimal(String.valueOf(value)).doubleValue();
    }

    private static long longNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
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
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    public record SalesPayload(
            Meta meta,
            Summary summary,
            List<DailyRow> daily,
            List<DailyRow> trendDaily,
            List<ParentRow> parentRows,
            List<SkuRow> skuRows,
            List<DimensionRow> colors,
            List<DimensionRow> sizes
    ) {
    }

    public record Meta(
            LocalDate firstDate,
            LocalDate latestDate,
            LocalDate currentStart,
            LocalDate currentEnd,
            LocalDate previousStart,
            LocalDate previousEnd,
            int periodDays,
            int sampleDays,
            String currency,
            String priceSource,
            String dateSource,
            LocalDateTime inventorySnapshotAt
    ) {
    }

    public record Summary(
            double units,
            double revenue,
            long orders,
            double dailyUnits,
            double dailyRevenue,
            double averageUnitPrice,
            double averageOrderValue,
            int activeSkus,
            int activeParents,
            int knownSkus,
            double activeRate,
            Double unitsChangePct,
            Double revenueChangePct,
            Double ordersChangePct,
            Double activeSkuChangePct,
            Double averagePriceChangePct
    ) {
    }

    public record DailyRow(
            LocalDate date,
            double units,
            double revenue,
            long orders,
            double averageUnitPrice,
            double adSpend,
            double roas
    ) {
    }

    public record SkuRow(
            String sku,
            String parentSku,
            String itemId,
            String itemName,
            String imageUrl,
            String color,
            String size,
            double units,
            double revenue,
            long orders,
            double previousUnits,
            double previousRevenue,
            Double unitsChangePct,
            Double revenueChangePct,
            double averageUnitPrice,
            double dailyUnits,
            double sharePct,
            double availableUnits,
            double reservedUnits,
            double transitUnits,
            Double availableDays,
            String status
    ) {
    }

    public record ParentRow(
            String parentSku,
            String itemName,
            String imageUrl,
            int variantCount,
            int colorCount,
            int sizeCount,
            double units,
            double revenue,
            long orders,
            double previousUnits,
            double previousRevenue,
            Double unitsChangePct,
            Double revenueChangePct,
            double averageUnitPrice,
            double dailyUnits,
            double sharePct,
            double availableUnits,
            double reservedUnits,
            double transitUnits,
            Double availableDays,
            String status
    ) {
    }

    public record DimensionRow(
            String label,
            double units,
            double revenue,
            double sharePct,
            int skuCount
    ) {
    }

    private record SalesAggregate(
            String sku,
            String parentSku,
            String itemName,
            double currentUnits,
            double currentRevenue,
            long currentOrders,
            double previousUnits,
            double previousRevenue,
            long previousOrders
    ) {
        private static SalesAggregate empty(String sku) {
            return new SalesAggregate(sku, null, null, 0, 0, 0, 0, 0, 0);
        }
    }

    private record ProductInfo(
            String sku,
            String parentSku,
            String productName,
            String imageUrl,
            String color,
            String size
    ) {
    }

    private record InventoryInfo(
            String sku,
            String parentSku,
            String itemId,
            String itemName,
            double availableUnits,
            double reservedUnits,
            double transitUnits
    ) {
    }

    private record InventorySnapshotData(
            LocalDateTime snapshotAt,
            Map<String, InventoryInfo> rows
    ) {
    }

    private static final class ParentAccumulator {
        private final String parentSku;
        private String itemName;
        private String imageUrl;
        private int variantCount;
        private final Set<String> colors = new LinkedHashSet<>();
        private final Set<String> sizes = new LinkedHashSet<>();
        private double units;
        private double revenue;
        private long orders;
        private double previousUnits;
        private double previousRevenue;
        private double availableUnits;
        private double reservedUnits;
        private double transitUnits;

        private ParentAccumulator(String parentSku) {
            this.parentSku = parentSku;
        }

        private void add(SkuRow row) {
            itemName = firstText(itemName, row.itemName());
            imageUrl = firstText(imageUrl, row.imageUrl());
            variantCount++;
            if (row.color() != null && !row.color().isBlank()) {
                colors.add(row.color().trim());
            }
            if (row.size() != null && !row.size().isBlank()) {
                sizes.add(row.size().trim());
            }
            units += row.units();
            revenue += row.revenue();
            orders += row.orders();
            previousUnits += row.previousUnits();
            previousRevenue += row.previousRevenue();
            availableUnits += row.availableUnits();
            reservedUnits += row.reservedUnits();
            transitUnits += row.transitUnits();
        }

        private ParentRow toRow(int days, double totalUnits) {
            double dailyUnits = units / days;
            return new ParentRow(
                    parentSku,
                    firstText(itemName, parentSku),
                    imageUrl,
                    variantCount,
                    colors.size(),
                    sizes.size(),
                    round(units),
                    round(revenue),
                    orders,
                    round(previousUnits),
                    round(previousRevenue),
                    change(units, previousUnits),
                    change(revenue, previousRevenue),
                    round(divide(revenue, units)),
                    round(dailyUnits),
                    round(divide(units, totalUnits)),
                    round(availableUnits),
                    round(reservedUnits),
                    round(transitUnits),
                    dailyUnits > 0 ? round(availableUnits / dailyUnits) : null,
                    status(units, previousUnits)
            );
        }
    }

    private static final class DimensionAccumulator {
        private final String label;
        private double units;
        private double revenue;
        private int skuCount;

        private DimensionAccumulator(String label) {
            this.label = label;
        }

        private void add(SkuRow row) {
            units += row.units();
            revenue += row.revenue();
            skuCount++;
        }

        private DimensionRow toRow(double totalUnits) {
            return new DimensionRow(
                    label,
                    round(units),
                    round(revenue),
                    round(divide(units, totalUnits)),
                    skuCount
            );
        }
    }
}
