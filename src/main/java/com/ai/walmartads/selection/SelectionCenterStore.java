package com.ai.walmartads.selection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
class SelectionCenterStore {
    private final JdbcTemplate jdbc;

    SelectionCenterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Monitor(long id, String sourceUrl, String zip) {}
    record Batch(long id, LocalDate date, Instant observedAt, int expectedPages, int completedPages,
                 boolean complete, boolean inventoryComplete, Integer declaredCount, String issue) {}
    record Day(LocalDate date, long runId, Long previousRunId, boolean published) {}
    record Style(long id, String itemId, String title, String brand, String category, String imageUrl,
                 BigDecimal price, Integer sourcePosition, int expectedSkus, boolean groupVerified,
                 LocalDate firstSeen) {}
    record Variant(long styleId, SelectionSnapshot.Variant value) {}
    record StockQuantities(long total, int knownSkus) {
        static final StockQuantities EMPTY = new StockQuantities(0, 0);

        StockQuantities with(int quantity) {
            return new StockQuantities(total + quantity, knownSkus + 1);
        }
    }
    record DailyResult(long styleId, Long sales, Long sevenDaySales, int stockIncreaseSkus,
                       long stockIncreaseUnits, int comparableSkus, int expectedSkus,
                       Integer rank, Integer categoryRank) {}
    record Point(LocalDate date, Integer rank, Long sales, BigDecimal price) {}

    Monitor lockMonitor(String hash, String url, String zip) {
        jdbc.update("""
                INSERT INTO wm_selection_monitor (source_key, source_url, zip_code) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE source_key = source_key
                """, hash, url, zip);
        return jdbc.queryForObject("""
                SELECT id, source_url, zip_code FROM wm_selection_monitor WHERE source_key = ? FOR UPDATE
                """, monitorMapper(), hash);
    }

    Monitor latestMonitor() {
        return first(jdbc.query("SELECT id, source_url, zip_code FROM wm_selection_monitor ORDER BY id DESC LIMIT 1",
                monitorMapper()));
    }

    private RowMapper<Monitor> monitorMapper() {
        return (row, index) -> new Monitor(row.getLong("id"), row.getString("source_url"), row.getString("zip_code"));
    }

    Batch insertBatch(long monitorId, SelectionSnapshot input, Instant at, String payloadHash) {
        jdbc.update("""
                INSERT INTO wm_selection_run (monitor_id, business_date, observed_at, expected_pages,
                    completed_pages, listing_complete, inventory_complete, declared_count, issue, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, monitorId, input.date(), Timestamp.from(at), input.expectedPages(), input.completedPages(),
                input.complete(), input.inventoryComplete(), input.declaredCount(), input.issue(), payloadHash);
        return findBatch(monitorId, input.date(), payloadHash);
    }

    Batch findBatch(long monitorId, LocalDate date, String payloadHash) {
        return first(jdbc.query("""
                SELECT * FROM wm_selection_run WHERE monitor_id = ? AND business_date = ? AND payload_hash = ?
                """, batchMapper(), monitorId, date, payloadHash));
    }

    Batch batch(long id) {
        return jdbc.queryForObject("SELECT * FROM wm_selection_run WHERE id = ?", batchMapper(), id);
    }

    Batch latestBatch(long monitorId) {
        return first(jdbc.query("""
                SELECT * FROM wm_selection_run WHERE monitor_id = ? ORDER BY business_date DESC, id DESC LIMIT 1
                """, batchMapper(), monitorId));
    }

    private RowMapper<Batch> batchMapper() {
        return (row, index) -> new Batch(row.getLong("id"), row.getObject("business_date", LocalDate.class),
                row.getTimestamp("observed_at").toInstant(), row.getInt("expected_pages"),
                row.getInt("completed_pages"), row.getBoolean("listing_complete"), row.getBoolean("inventory_complete"),
                row.getObject("declared_count", Integer.class), row.getString("issue"));
    }

    long resolveStyle(long monitorId, String key, boolean verified, LocalDate date, List<String> verifiedMembers) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM wm_selection_style WHERE monitor_id = ? AND style_key = ?",
                Long.class, monitorId, key);
        List<Alias> aliases = verified ? unverifiedMembers(monitorId, verifiedMembers) : List.of();
        long id;
        if (!existing.isEmpty()) {
            id = existing.getFirst();
        } else if (!aliases.isEmpty()) {
            id = aliases.getFirst().id();
            jdbc.update("UPDATE wm_selection_style SET style_key = ?, group_verified = TRUE WHERE id = ?", key, id);
        } else {
            jdbc.update("INSERT INTO wm_selection_style (monitor_id, style_key, group_verified, first_seen) VALUES (?, ?, ?, ?)",
                    monitorId, key, verified, date);
            id = jdbc.queryForObject("SELECT id FROM wm_selection_style WHERE monitor_id = ? AND style_key = ?",
                    Long.class, monitorId, key);
        }
        LocalDate firstSeen = aliases.isEmpty() || !aliases.getFirst().date().isBefore(date) ? date : aliases.getFirst().date();
        jdbc.update("UPDATE wm_selection_style SET first_seen = ? WHERE id = ? AND first_seen > ?", firstSeen, id, firstSeen);
        for (Alias alias : aliases) {
            if (id == alias.id()) continue;
            jdbc.update("UPDATE wm_selection_style SET canonical_style_id = ? WHERE id = ?", id, alias.id());
            for (String account : jdbc.queryForList("SELECT account_name FROM wm_selection_watch WHERE style_id = ?",
                    String.class, alias.id())) {
                setWatch(account, id, true);
                setWatch(account, alias.id(), false);
            }
        }
        return id;
    }

    private record Alias(long id, LocalDate date) {}

    private List<Alias> unverifiedMembers(long monitor, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(monitor);
        ids.forEach(id -> args.add("item:" + id));
        return jdbc.query("""
                SELECT id, first_seen FROM wm_selection_style WHERE monitor_id = ? AND group_verified = FALSE
                  AND canonical_style_id IS NULL AND style_key IN (%s) ORDER BY first_seen, id
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (row, index) -> new Alias(row.getLong("id"), row.getObject("first_seen", LocalDate.class)), args.toArray());
    }

    void insertStyle(long runId, long styleId, String itemId, SelectionSnapshot.Item item, int expectedSkus,
                     boolean groupVerified) {
        jdbc.update("""
                INSERT INTO wm_selection_style_snapshot (run_id, style_id, item_id, title, brand, category,
                    image_url, price, source_position, expected_skus, group_verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, styleId, itemId, item.title(), item.brand(), item.category(), item.imageUrl(),
                item.price(), item.sourcePosition(), expectedSkus, groupVerified);
    }

    void insertVariants(long runId, List<Variant> variants) {
        jdbc.batchUpdate("""
                INSERT INTO wm_selection_sku_snapshot (run_id, style_id, item_id, product_id, color_name, size_name,
                    order_limit, max_order_quantity, availability, seller_id, offer_id, fulfillment, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, variants, 500, (stmt, member) -> {
            SelectionSnapshot.Variant v = member.value();
            Object[] values = {runId, member.styleId(), v.itemId(), v.productId(), v.color(), v.size(),
                    v.orderLimit(), v.maxOrderQuantity(), v.availability(), v.sellerId(), v.offerId(), v.fulfillment(),
                    v.observedAt() == null ? null : Timestamp.from(v.observedAt())};
            for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
        });
    }

    List<Style> styles(long runId) {
        return jdbc.query("""
                SELECT v.*, s.first_seen FROM wm_selection_style_snapshot v
                JOIN wm_selection_style s ON s.id = v.style_id WHERE v.run_id = ?
                ORDER BY v.source_position IS NULL, v.source_position, v.style_id
                """, (row, index) -> new Style(row.getLong("style_id"), row.getString("item_id"), row.getString("title"),
                row.getString("brand"), row.getString("category"), row.getString("image_url"), row.getBigDecimal("price"),
                row.getObject("source_position", Integer.class), row.getInt("expected_skus"),
                row.getBoolean("group_verified"), row.getObject("first_seen", LocalDate.class)), runId);
    }

    List<Variant> variants(long runId) {
        return variants(runId, null);
    }

    Map<Long, StockQuantities> stockQuantities(long runId, Long styleId) {
        // Use the recorded style membership for this exact batch, including in historical views.
        String sql = "SELECT style_id, order_limit, max_order_quantity FROM wm_selection_sku_snapshot WHERE run_id = ?";
        Object[] args = {runId};
        if (styleId != null) {
            sql += " AND style_id = ?";
            args = new Object[]{runId, styleId};
        }
        return jdbc.query(sql, rows -> {
            Map<Long, StockQuantities> byStyle = new LinkedHashMap<>();
            while (rows.next()) {
                Integer quantity = SelectionCenterRules.quantity(rows.getObject("order_limit", Integer.class),
                        rows.getObject("max_order_quantity", Integer.class));
                if (quantity != null) {
                    long recordedStyle = rows.getLong("style_id");
                    StockQuantities stock = byStyle.getOrDefault(recordedStyle, StockQuantities.EMPTY);
                    byStyle.put(recordedStyle, stock.with(quantity));
                }
            }
            return byStyle;
        }, args);
    }

    List<Variant> variants(long runId, Long styleId) {
        String sql = """
                SELECT v.*, COALESCE(s.canonical_style_id, s.id) AS canonical_id FROM wm_selection_sku_snapshot v
                JOIN wm_selection_style s ON s.id = v.style_id WHERE v.run_id = ?
                """;
        Object[] args = {runId};
        if (styleId != null) {
            sql += " AND (v.style_id = ? OR s.canonical_style_id = ?)";
            args = new Object[]{runId, styleId, styleId};
        }
        return jdbc.query(sql, (row, index) -> {
            Timestamp at = row.getTimestamp("observed_at");
            SelectionSnapshot.Variant variant = new SelectionSnapshot.Variant(row.getString("item_id"),
                    row.getString("product_id"), row.getString("color_name"), row.getString("size_name"),
                    row.getObject("order_limit", Integer.class), row.getObject("max_order_quantity", Integer.class),
                    row.getString("availability"), row.getString("seller_id"), row.getString("offer_id"),
                    row.getString("fulfillment"), at == null ? null : at.toInstant());
            return new Variant(row.getLong("canonical_id"), variant);
        }, args);
    }

    Day day(long monitorId, LocalDate date) {
        return first(jdbc.query("SELECT * FROM wm_selection_day WHERE monitor_id = ? AND business_date = ?",
                dayMapper(), monitorId, date));
    }

    Day latestPublished(long monitorId) {
        return first(jdbc.query("""
                SELECT * FROM wm_selection_day WHERE monitor_id = ? AND published = TRUE
                ORDER BY business_date DESC LIMIT 1
                """, dayMapper(), monitorId));
    }

    List<LocalDate> dates(long monitorId) {
        return jdbc.query("SELECT business_date FROM wm_selection_day WHERE monitor_id = ? ORDER BY business_date DESC",
                (row, index) -> row.getObject(1, LocalDate.class), monitorId);
    }

    private RowMapper<Day> dayMapper() {
        return (row, index) -> new Day(row.getObject("business_date", LocalDate.class), row.getLong("run_id"),
                row.getObject("previous_run_id", Long.class), row.getBoolean("published"));
    }

    void setDay(long monitorId, LocalDate date, long runId, Long previousRunId, boolean published, String ruleVersion) {
        int changed = jdbc.update("""
                UPDATE wm_selection_day SET run_id = ?, previous_run_id = ?, published = ?, rule_version = ?
                WHERE monitor_id = ? AND business_date = ?
                """, runId, previousRunId, published, ruleVersion, monitorId, date);
        if (changed == 0 && day(monitorId, date) == null) {
            jdbc.update("""
                    INSERT INTO wm_selection_day (monitor_id, business_date, run_id, previous_run_id, published, rule_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, monitorId, date, runId, previousRunId, published, ruleVersion);
        }
    }

    List<DailyResult> results(long monitorId, LocalDate date) {
        return jdbc.query("SELECT * FROM wm_selection_daily_result WHERE monitor_id = ? AND business_date = ?",
                (row, index) -> new DailyResult(row.getLong("style_id"), row.getObject("sales", Long.class),
                row.getObject("sales_7d", Long.class), row.getInt("stock_increase_skus"),
                row.getLong("stock_increase_units"), row.getInt("comparable_skus"), row.getInt("expected_skus"),
                row.getObject("overall_rank", Integer.class), row.getObject("category_rank", Integer.class)),
                monitorId, date);
    }

    record PriorSales(long styleId, long total) {}

    List<PriorSales> priorSixDaySales(long monitorId, LocalDate date) {
        return jdbc.query("""
                SELECT r.style_id, SUM(r.sales) AS total FROM wm_selection_daily_result r
                JOIN wm_selection_day d ON d.monitor_id = r.monitor_id AND d.business_date = r.business_date
                WHERE r.monitor_id = ? AND r.business_date >= ? AND r.business_date < ?
                    AND d.published = TRUE AND r.sales IS NOT NULL GROUP BY r.style_id
                """, (row, index) -> new PriorSales(row.getLong("style_id"), row.getLong("total")),
                monitorId, date.minusDays(6), date);
    }

    void replaceResults(long monitorId, LocalDate date, List<DailyResult> rows) {
        jdbc.update("DELETE FROM wm_selection_daily_result WHERE monitor_id = ? AND business_date = ?", monitorId, date);
        jdbc.batchUpdate("""
                INSERT INTO wm_selection_daily_result (monitor_id, business_date, style_id, sales, sales_7d,
                    stock_increase_skus, stock_increase_units, comparable_skus, expected_skus, overall_rank, category_rank)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, 500, (stmt, row) -> {
            Object[] values = {monitorId, date, row.styleId(), row.sales(), row.sevenDaySales(), row.stockIncreaseSkus(),
                    row.stockIncreaseUnits(), row.comparableSkus(), row.expectedSkus(), row.rank(), row.categoryRank()};
            for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
        });
    }

    List<Point> history(long monitorId, long styleId, LocalDate date) {
        List<Point> points = jdbc.query("""
                SELECT d.business_date, r.overall_rank, r.sales, s.price FROM wm_selection_day d
                JOIN wm_selection_style_snapshot s ON s.run_id = d.run_id
                JOIN wm_selection_style identity ON identity.id = s.style_id
                LEFT JOIN wm_selection_daily_result r ON r.monitor_id = d.monitor_id
                    AND r.business_date = d.business_date AND r.style_id = s.style_id
                WHERE (identity.id = ? OR identity.canonical_style_id = ?) AND d.monitor_id = ?
                    AND d.business_date BETWEEN ? AND ? ORDER BY d.business_date, identity.group_verified DESC,
                        s.source_position IS NULL, s.source_position, identity.id
                """, (row, index) -> new Point(row.getObject("business_date", LocalDate.class),
                row.getObject("overall_rank", Integer.class), row.getObject("sales", Long.class),
                row.getBigDecimal("price")), styleId, styleId, monitorId, date.minusDays(29), date);
        Map<LocalDate, Point> best = new LinkedHashMap<>();
        for (Point point : points) best.putIfAbsent(point.date(), point);
        return List.copyOf(best.values());
    }

    Long resolveWatchStyle(long monitorId, String itemId) {
        return first(jdbc.queryForList("""
                SELECT COALESCE(identity.canonical_style_id, identity.id) FROM wm_selection_style_snapshot s
                JOIN wm_selection_style identity ON identity.id = s.style_id
                JOIN wm_selection_day d ON d.run_id = s.run_id
                WHERE d.monitor_id = ? AND s.item_id = ? ORDER BY d.business_date DESC LIMIT 1
                """, Long.class, monitorId, itemId));
    }

    List<Long> watched(String account) {
        return jdbc.queryForList("SELECT style_id FROM wm_selection_watch WHERE account_name = ?", Long.class, account);
    }

    void setWatch(String account, long styleId, boolean watched) {
        if (watched) {
            jdbc.update("""
                    INSERT INTO wm_selection_watch (account_name, style_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE style_id = style_id
                    """, account, styleId);
        } else {
            jdbc.update("DELETE FROM wm_selection_watch WHERE account_name = ? AND style_id = ?", account, styleId);
        }
    }

    private static <T> T first(List<T> list) {
        return list.isEmpty() ? null : list.getFirst();
    }
}
