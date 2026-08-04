package com.ai.walmartads.operations.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OperationRecordService {

    private static final Set<String> TARGET_TYPES = Set.of("PARENT_SKU", "SKU");
    private static final Set<String> ACTION_TYPES = Set.of(
            "NOTE",
            "LISTING_TITLE",
            "LISTING_IMAGES",
            "LISTING_CONTENT",
            "PRICE",
            "AD_BID",
            "AD_BUDGET",
            "AD_KEYWORDS",
            "AD_STATUS",
            "PROMOTION",
            "INVENTORY",
            "OTHER"
    );
    private static final Set<String> SOURCE_TYPES = Set.of("MANUAL", "SYSTEM", "AI");
    private static final Set<String> STATUSES = Set.of("PLANNED", "EXECUTED", "CANCELLED");

    private final NamedParameterJdbcTemplate jdbc;
    private final OperationWindowCalculator windowCalculator;

    public OperationRecordService(
            NamedParameterJdbcTemplate jdbc,
            OperationWindowCalculator windowCalculator
    ) {
        this.jdbc = jdbc;
        this.windowCalculator = windowCalculator;
    }

    @Transactional
    public OperationRecord create(CreateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("运营记录不能为空。");
        }
        String targetType = enumValue(command.targetType(), TARGET_TYPES, "作用范围");
        String targetKey = required(command.targetKey(), "SKU 或款号", 180);
        String actionType = enumValue(command.actionType(), ACTION_TYPES, "操作类型");
        String sourceType = optionalEnum(command.sourceType(), SOURCE_TYPES, "MANUAL", "执行来源");
        String status = optionalEnum(command.status(), STATUSES, "EXECUTED", "执行状态");
        String title = required(command.title(), "操作摘要", 240);
        String beforeValue = optional(command.beforeValue(), 4000, "调整前内容");
        String afterValue = optional(command.afterValue(), 4000, "调整后内容");
        String note = optional(command.note(), 8000, "备注");
        String externalReference = optional(command.externalReference(), 240, "外部关联号");
        LocalDateTime occurredAt = command.occurredAt() == null ? LocalDateTime.now() : command.occurredAt();
        if (occurredAt.isAfter(LocalDateTime.now().plusDays(1))) {
            throw new IllegalArgumentException("操作时间不能晚于明天。");
        }

        TargetIdentity identity = resolveTarget(targetType, targetKey);
        LocalDateTime now = LocalDateTime.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", targetType)
                .addValue("targetKey", targetKey)
                .addValue("parentSku", identity.parentSku())
                .addValue("targetName", identity.targetName())
                .addValue("actionType", actionType)
                .addValue("sourceType", sourceType)
                .addValue("status", status)
                .addValue("occurredAt", occurredAt)
                .addValue("title", title)
                .addValue("beforeValue", beforeValue)
                .addValue("afterValue", afterValue)
                .addValue("note", note)
                .addValue("externalReference", externalReference)
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into wm_ads_operation_record (
                    target_type, target_key, parent_sku, target_name,
                    action_type, source_type, status, occurred_at,
                    title, before_value, after_value, note, external_reference,
                    created_at, updated_at
                ) values (
                    :targetType, :targetKey, :parentSku, :targetName,
                    :actionType, :sourceType, :status, :occurredAt,
                    :title, :beforeValue, :afterValue, :note, :externalReference,
                    :createdAt, :updatedAt
                )
                """, params, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("运营记录保存成功，但未返回记录编号。");
        }
        return findRecord(id.longValue());
    }

    @Transactional(readOnly = true)
    public OperationPayload list(
            int days,
            String targetType,
            String targetKey,
            String query,
            int limit
    ) {
        return list(days, null, null, targetType, targetKey, query, limit);
    }

    @Transactional(readOnly = true)
    public OperationPayload list(
            int days,
            LocalDate startDate,
            LocalDate endDate,
            String targetType,
            String targetKey,
            String query,
            int limit
    ) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("自定义时间范围必须同时提供开始日期和结束日期。");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        }
        int safeDays = startDate == null
                ? Math.max(1, Math.min(days, 90))
                : Math.max(1, Math.min(
                        Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1),
                        90
                ));
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String safeTargetType = blank(targetType) ? null : enumValue(targetType, TARGET_TYPES, "作用范围");
        String safeTargetKey = blank(targetKey) ? null : targetKey.trim();
        String safeQuery = blank(query) ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", safeTargetType)
                .addValue("targetKey", safeTargetKey)
                .addValue("query", safeQuery)
                .addValue("rangeStart", startDate == null ? null : startDate.atStartOfDay())
                .addValue("rangeEnd", endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .addValue("limit", safeLimit);
        List<OperationRecord> records = jdbc.queryForList("""
                select id, target_type, target_key, parent_sku, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where (:targetType is null or target_type = :targetType)
                   and (:targetKey is null or target_key = :targetKey)
                   and (:rangeStart is null or occurred_at >= :rangeStart)
                   and (:rangeEnd is null or occurred_at < :rangeEnd)
                   and (:query is null
                        or lower(target_key) like :query
                        or lower(coalesce(parent_sku, '')) like :query
                        or lower(coalesce(target_name, '')) like :query
                        or lower(title) like :query
                        or lower(coalesce(note, '')) like :query)
                 order by occurred_at desc, id desc
                 limit :limit
                """, params).stream().map(this::mapRecord).toList();

        LocalDate latestDataDate = latestDataDate();
        List<OperationWithImpact> items = records.stream()
                .map(record -> new OperationWithImpact(record, impact(record, latestDataDate, safeDays)))
                .toList();
        long executed = records.stream().filter(record -> "EXECUTED".equals(record.status())).count();
        long system = records.stream()
                .filter(record -> "EXECUTED".equals(record.status()))
                .filter(record -> !"MANUAL".equals(record.sourceType()))
                .count();
        long ready = items.stream().filter(item -> "READY".equals(item.impact().state())).count();
        return new OperationPayload(
                new OperationMeta(safeDays, startDate, endDate, latestDataDate, records.size(), executed, system, ready),
                items
        );
    }

    @Transactional(readOnly = true)
    public List<TargetOption> targets(String query, int limit) {
        String safeQuery = blank(query) ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.queryForList("""
                select target_type, target_key, parent_sku, target_name
                  from (
                        select 'PARENT_SKU' as target_type,
                               mapping.parent_sku as target_key,
                               mapping.parent_sku as parent_sku,
                               max(mapping.item_name) as target_name
                          from wm_ads_sku_mapping mapping
                         where mapping.current_version = true
                         group by mapping.parent_sku
                        union all
                        select 'SKU' as target_type,
                               mapping.sku as target_key,
                               mapping.parent_sku as parent_sku,
                               max(mapping.item_name) as target_name
                          from wm_ads_sku_mapping mapping
                         where mapping.current_version = true
                         group by mapping.sku, mapping.parent_sku
                       ) targets
                 order by target_type, target_key
                """, new MapSqlParameterSource()).stream()
                .map(row -> new TargetOption(
                        text(row, "target_type"),
                        text(row, "target_key"),
                        text(row, "parent_sku"),
                        text(row, "target_name")
                ))
                .filter(option -> safeQuery.isEmpty()
                        || option.targetKey().toLowerCase(Locale.ROOT).contains(safeQuery)
                        || lower(option.targetName()).contains(safeQuery))
                .limit(safeLimit)
                .toList();
    }

    private OperationImpact impact(OperationRecord record, LocalDate latestDataDate, int days) {
        if (!"EXECUTED".equals(record.status())) {
            return new OperationImpact(
                    "NOT_EXECUTED", days, 0, latestDataDate,
                    null, null, 0,
                    "只有标记为已执行的操作才计算效果。"
            );
        }
        OperationWindowCalculator.EvaluationWindow window = windowCalculator.calculate(
                record.occurredAt().toLocalDate(),
                latestDataDate,
                days
        );
        MetricSnapshot before = metrics(
                record.targetType(),
                record.targetKey(),
                window.beforeStart(),
                window.beforeEnd(),
                window.comparedDays() > 0 ? window.comparedDays() : window.requestedDays()
        );
        MetricSnapshot after = window.comparedDays() == 0
                ? null
                : metrics(
                        record.targetType(),
                        record.targetKey(),
                        window.afterStart(),
                        window.afterEnd(),
                        window.comparedDays()
                );
        int overlaps = window.comparedDays() == 0
                ? 0
                : overlappingOperations(record, window.beforeStart(), window.afterEnd());
        String message = switch (window.state()) {
            case "WAITING" -> "等待导入操作日之后的数据；当前仅展示操作前基线。";
            case "COLLECTING" -> "后续数据仍在积累，当前按前后等长天数进行阶段性比较。";
            default -> "已获得完整前后等长窗口，可用于复盘；相关变化不等于绝对因果。";
        };
        return new OperationImpact(
                window.state(),
                window.requestedDays(),
                window.comparedDays(),
                latestDataDate,
                before,
                after,
                overlaps,
                message
        );
    }

    private MetricSnapshot metrics(
            String targetType,
            String targetKey,
            LocalDate start,
            LocalDate end,
            int calendarDays
    ) {
        String orderTarget = "PARENT_SKU".equals(targetType)
                ? "coalesce(orders.parent_sku, orders.sku)"
                : "orders.sku";
        String adTarget = "PARENT_SKU".equals(targetType)
                ? "ads.parent_sku"
                : "ads.sku";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetKey", targetKey)
                .addValue("start", start)
                .addValue("end", end);
        Map<String, Object> order = jdbc.queryForMap("""
                select coalesce(sum(orders.quantity), 0) as units,
                       coalesce(sum(orders.quantity * orders.item_cost), 0) as sales
                  from wm_ads_order_record orders
                  join wm_ads_import_file file
                    on file.id = orders.import_file_id and file.active = true
                 where orders.report_date between :start and :end
                   and %s = :targetKey
                """.formatted(orderTarget), params);
        Map<String, Object> ad = jdbc.queryForMap("""
                select coalesce(sum(ads.impressions), 0) as impressions,
                       coalesce(sum(ads.clicks), 0) as clicks,
                       coalesce(sum(ads.ad_spend), 0) as spend,
                       coalesce(sum(ads.orders), 0) as ad_orders,
                       coalesce(sum(ads.attributed_sales), 0) as ad_sales
                  from wm_ads_ad_performance ads
                  join wm_ads_import_file file
                    on file.id = ads.import_file_id and file.active = true
                 where file.report_kind = 'ITEM_PERFORMANCE'
                   and ads.report_date between :start and :end
                   and %s = :targetKey
                """.formatted(adTarget), params);
        double units = number(order, "units");
        double sales = number(order, "sales");
        double impressions = number(ad, "impressions");
        double clicks = number(ad, "clicks");
        double spend = number(ad, "spend");
        double adOrders = number(ad, "ad_orders");
        double adSales = number(ad, "ad_sales");
        int safeCalendarDays = Math.max(calendarDays, 1);
        return new MetricSnapshot(
                start,
                end,
                safeCalendarDays,
                round(units),
                round(sales),
                round(impressions),
                round(clicks),
                round(spend),
                round(adOrders),
                round(adSales),
                round(spend == 0 ? 0 : adSales / spend),
                round(clicks == 0 ? 0 : spend / clicks),
                round(units / safeCalendarDays),
                round(sales / safeCalendarDays),
                round(spend / safeCalendarDays),
                round(adSales / safeCalendarDays)
        );
    }

    private int overlappingOperations(OperationRecord record, LocalDate start, LocalDate end) {
        Integer result = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_operation_record other
                 where other.id <> :id
                   and other.status = 'EXECUTED'
                   and other.occurred_at >= :startAt
                   and other.occurred_at < :endAt
                   and (
                        (:parentSku is not null and other.parent_sku = :parentSku)
                        or (:parentSku is null
                            and other.target_type = :targetType
                            and other.target_key = :targetKey)
                   )
                """, new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("parentSku", record.parentSku())
                .addValue("targetType", record.targetType())
                .addValue("targetKey", record.targetKey())
                .addValue("startAt", start.atStartOfDay())
                .addValue("endAt", end.plusDays(1).atStartOfDay()), Integer.class);
        return result == null ? 0 : result;
    }

    private LocalDate latestDataDate() {
        return jdbc.query("""
                select max(source.latest_date) as latest_date
                  from (
                        select max(orders.report_date) as latest_date
                          from wm_ads_order_record orders
                          join wm_ads_import_file file
                            on file.id = orders.import_file_id and file.active = true
                        union all
                        select max(ads.report_date) as latest_date
                          from wm_ads_ad_performance ads
                          join wm_ads_import_file file
                            on file.id = ads.import_file_id and file.active = true
                         where file.report_kind = 'ITEM_PERFORMANCE'
                       ) source
                """, new MapSqlParameterSource(), resultSet ->
                resultSet.next() && resultSet.getDate("latest_date") != null
                        ? resultSet.getDate("latest_date").toLocalDate()
                        : null
        );
    }

    private TargetIdentity resolveTarget(String targetType, String targetKey) {
        String where = "PARENT_SKU".equals(targetType)
                ? "mapping.parent_sku = :targetKey"
                : "mapping.sku = :targetKey";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select mapping.parent_sku, max(mapping.item_name) as target_name
                  from wm_ads_sku_mapping mapping
                 where mapping.current_version = true
                   and %s
                 group by mapping.parent_sku
                 limit 1
                """.formatted(where), new MapSqlParameterSource("targetKey", targetKey));
        if (rows.isEmpty()) {
            String parentSku = "PARENT_SKU".equals(targetType) ? targetKey : null;
            return new TargetIdentity(parentSku, null);
        }
        return new TargetIdentity(
                text(rows.getFirst(), "parent_sku"),
                text(rows.getFirst(), "target_name")
        );
    }

    private OperationRecord findRecord(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, target_type, target_key, parent_sku, target_name,
                       action_type, source_type, status, occurred_at,
                       title, before_value, after_value, note, external_reference,
                       created_at, updated_at
                  from wm_ads_operation_record
                 where id = :id
                """, new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到运营记录 #" + id);
        }
        return mapRecord(rows.getFirst());
    }

    private OperationRecord mapRecord(Map<String, Object> row) {
        return new OperationRecord(
                longNumber(row, "id"),
                text(row, "target_type"),
                text(row, "target_key"),
                text(row, "parent_sku"),
                text(row, "target_name"),
                text(row, "action_type"),
                text(row, "source_type"),
                text(row, "status"),
                localDateTime(row.get("occurred_at")),
                text(row, "title"),
                text(row, "before_value"),
                text(row, "after_value"),
                text(row, "note"),
                text(row, "external_reference"),
                localDateTime(row.get("created_at")),
                localDateTime(row.get("updated_at"))
        );
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, label, 40).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + "不受支持：" + value);
        }
        return normalized;
    }

    private static String optionalEnum(String value, Set<String> allowed, String defaultValue, String label) {
        return blank(value) ? defaultValue : enumValue(value, allowed, label);
    }

    private static String required(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空。");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符。");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String label) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符。");
        }
        return normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static long longNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().atStartOfDay();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private record TargetIdentity(String parentSku, String targetName) {
    }

    public record CreateCommand(
            String targetType,
            String targetKey,
            String actionType,
            String sourceType,
            String status,
            LocalDateTime occurredAt,
            String title,
            String beforeValue,
            String afterValue,
            String note,
            String externalReference
    ) {
    }

    public record OperationRecord(
            long id,
            String targetType,
            String targetKey,
            String parentSku,
            String targetName,
            String actionType,
            String sourceType,
            String status,
            LocalDateTime occurredAt,
            String title,
            String beforeValue,
            String afterValue,
            String note,
            String externalReference,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record TargetOption(
            String targetType,
            String targetKey,
            String parentSku,
            String targetName
    ) {
    }

    public record MetricSnapshot(
            LocalDate startDate,
            LocalDate endDate,
            int calendarDays,
            double units,
            double sales,
            double impressions,
            double clicks,
            double adSpend,
            double adOrders,
            double adSales,
            double roas,
            double cpc,
            double unitsPerDay,
            double salesPerDay,
            double adSpendPerDay,
            double adSalesPerDay
    ) {
    }

    public record OperationImpact(
            String state,
            int requestedDays,
            int comparedDays,
            LocalDate latestDataDate,
            MetricSnapshot before,
            MetricSnapshot after,
            int overlappingOperations,
            String message
    ) {
    }

    public record OperationWithImpact(
            OperationRecord record,
            OperationImpact impact
    ) {
    }

    public record OperationMeta(
            int requestedDays,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestDataDate,
            long recordCount,
            long executedCount,
            long systemCount,
            long readyCount
    ) {
    }

    public record OperationPayload(
            OperationMeta meta,
            List<OperationWithImpact> items
    ) {
    }
}
