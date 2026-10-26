package com.ai.walmartads.wfs;

import com.ai.walmartads.wfs.WfsReportParser.ParsedReport;
import com.ai.walmartads.wfs.WfsReportParser.ParsedRow;
import com.ai.walmartads.wfs.WfsReportParser.RowError;
import com.ai.walmartads.wfs.WfsReportParser.SettlementRow;
import com.ai.walmartads.wfs.WfsReportParser.StorageRow;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WfsService {

    private static final String SEP = "\u001f";
    private static final DateTimeFormatter SQL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int HASH_CHUNK_SIZE = 500;
    private static final int MAX_FILE_BYTES = 100 * 1024 * 1024;
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WfsReportParser parser;

    public WfsService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, WfsReportParser parser) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.parser = parser;
    }

    @Transactional
    public ImportResult importReport(WfsReportType type, String originalName, byte[] content) {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        String fileName = safeFileName(originalName);
        validateFile(fileName, content);
        String fileHash = sha256(content);
        ImportResult existing = findActiveFile(type, fileHash);
        if (existing != null) return existing.withAlreadyImported(true);

        ParsedReport report;
        try {
            report = parser.parse(type, fileName, content);
        } catch (Exception exception) {
            long batchId = insertFailedBatch(type, fileName, fileHash, content.length, principal.username(), exception.getMessage());
            return new ImportResult(batchId, "FAILED", fileName, null, null, 0, 0, 0, 0,
                    false, exception.getMessage(), List.of());
        }

        ProductIndex products = productIndex();
        LinkedHashMap<String, PreparedRow> uniqueRows = new LinkedHashMap<>();
        int repeatedInsideFile = 0;
        for (ParsedRow row : report.rows()) {
            PreparedRow prepared = prepare(type, row, products, fileName, report.sourceStore());
            if (uniqueRows.putIfAbsent(prepared.hash(), prepared) != null) repeatedInsideFile++;
        }

        Set<String> hashes = uniqueRows.keySet();
        Map<String, Long> before = findRowIds(type, hashes);
        List<PreparedRow> newRows = uniqueRows.values().stream()
                .filter(row -> !before.containsKey(row.hash()))
                .toList();
        insertRows(type, newRows);
        Map<String, Long> rowIds = findRowIds(type, hashes);
        if (rowIds.size() != hashes.size()) {
            throw new IllegalStateException("部分 WFS 明细未能持久化，请稍后重试");
        }

        int duplicateRows = repeatedInsideFile + before.size();
        int failedRows = report.errors().size();
        String status = failedRows > 0 ? "PARTIAL" : "SUCCESS";
        Map<String, Object> summary = new LinkedHashMap<>(report.summary());
        summary.put("detailRowSum", uniqueRows.values().stream()
                .map(row -> row.amount() == null ? BigDecimal.ZERO : row.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (type == WfsReportType.STORAGE) {
            BigDecimal official = decimalSummary(report.summary(), "total storage fees");
            if (official != null) summary.put("difference", official.subtract((BigDecimal) summary.get("detailRowSum")));
        }

        long batchId = insertBatch(type, report, fileName, fileHash, content.length, status,
                report.totalRows(), newRows.size(), duplicateRows, failedRows, rowIds.size(),
                summary, report.errors(), principal.username(), null);
        insertBatchLinks(batchId, type, rowIds, before.keySet());
        String message = importMessage(newRows.size(), duplicateRows, failedRows);
        return new ImportResult(batchId, status, fileName, report.periodStart(), report.periodEnd(),
                report.totalRows(), newRows.size(), duplicateRows, failedRows, false, message,
                report.errors().stream().limit(200).toList());
    }

    public PageResult settlements(Query query) {
        return queryRows(WfsReportType.SETTLEMENT, query);
    }

    public PageResult storage(Query query) {
        return queryRows(WfsReportType.STORAGE, query);
    }

    public Map<String, Object> detail(WfsReportType type, long id) {
        String table = dataTable(type);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE id = :id",
                Map.of("id", id));
        if (rows.isEmpty()) throw new IllegalArgumentException("WFS 明细不存在或不属于当前店铺");
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.put("rawFields", readJson(row.remove("raw_fields")));
        return row;
    }

    public List<BatchView> batches(WfsReportType type, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT id, report_type, source_store, file_name, file_sha256, file_size,
                       period_start, period_end, status, total_rows, imported_rows, duplicate_rows,
                       failed_rows, linked_rows, summary_json, error_details, error_message,
                       created_by, imported_at, deleted_at
                  FROM wm_ads_wfs_import_batch
                 WHERE report_type = :type
                 ORDER BY imported_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource().addValue("type", type.name()).addValue("limit", safeLimit),
                (rs, rowNum) -> new BatchView(
                        rs.getLong("id"), WfsReportType.valueOf(rs.getString("report_type")),
                        rs.getString("source_store"), rs.getString("file_name"), rs.getString("file_sha256"),
                        rs.getLong("file_size"), date(rs.getObject("period_start")), date(rs.getObject("period_end")),
                        rs.getString("status"), rs.getInt("total_rows"), rs.getInt("imported_rows"),
                        rs.getInt("duplicate_rows"), rs.getInt("failed_rows"), rs.getInt("linked_rows"),
                        readJson(rs.getString("summary_json")), readJsonList(rs.getString("error_details")),
                        rs.getString("error_message"), rs.getString("created_by"),
                        rs.getTimestamp("imported_at").toLocalDateTime(),
                        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toLocalDateTime()
                ));
    }

    @Transactional
    public DeleteResult deleteBatch(long batchId) {
        List<Map<String, Object>> batches = jdbc.queryForList("""
                SELECT id, report_type, status FROM wm_ads_wfs_import_batch WHERE id = :id
                """, Map.of("id", batchId));
        if (batches.isEmpty()) throw new IllegalArgumentException("导入批次不存在或不属于当前店铺");
        Map<String, Object> batch = batches.getFirst();
        if ("DELETED".equals(batch.get("status"))) throw new IllegalArgumentException("该导入批次已经删除");
        WfsReportType type = WfsReportType.valueOf(String.valueOf(batch.get("report_type")));
        List<Long> rowIds = jdbc.queryForList("""
                SELECT row_id FROM wm_ads_wfs_batch_row
                 WHERE batch_id = :batchId AND report_type = :type
                """, new MapSqlParameterSource().addValue("batchId", batchId).addValue("type", type.name()), Long.class);
        int links = jdbc.update("DELETE FROM wm_ads_wfs_batch_row WHERE batch_id = :batchId", Map.of("batchId", batchId));
        int removedRows = 0;
        for (List<Long> chunk : chunks(rowIds, HASH_CHUNK_SIZE)) {
            removedRows += jdbc.update("DELETE FROM " + dataTable(type) + " d WHERE d.id IN (:ids) "
                            + "AND d.managed_by_batches = 1 AND NOT EXISTS (SELECT 1 FROM wm_ads_wfs_batch_row br "
                            + "WHERE br.workspace_id = :workspaceId AND br.report_type = :type AND br.row_id = d.id)",
                    new MapSqlParameterSource().addValue("ids", chunk).addValue("type", type.name())
                            .addValue("workspaceId", WorkspaceContext.requireId()));
        }
        jdbc.update("""
                UPDATE wm_ads_wfs_import_batch
                   SET status = 'DELETED', active_file_hash = NULL, deleted_at = NOW(6)
                 WHERE id = :id
                """, Map.of("id", batchId));
        return new DeleteResult(batchId, links, removedRows);
    }

    private PageResult queryRows(WfsReportType type, Query requested) {
        Query query = requested == null ? Query.empty() : requested.normalized();
        Period period = resolvePeriod(type, query.periodStart(), query.periodEnd());
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = filters(type, query, period, params);
        String table = dataTable(type);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " d " + where, params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql(type) + " " + where
                        + " ORDER BY " + sortColumn(type, query.sort()) + " " + query.direction()
                        + ", d.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", query.size()).addValue("offset", (long) query.page() * query.size()));
        Map<String, Object> summary = summary(type, where, params);
        List<Period> periods = periods(type);
        Map<String, Object> official = type == WfsReportType.STORAGE && period != null
                ? officialStorageSummary(period) : Map.of();
        return new PageResult(rows, total, query.page(), query.size(), period, periods, summary, official);
    }

    private String filters(WfsReportType type, Query query, Period period, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (period != null) {
            clauses.add("d.period_start = :periodStart AND d.period_end = :periodEnd");
            params.addValue("periodStart", period.start()).addValue("periodEnd", period.end());
        }
        if (query.transactionFrom() != null && type == WfsReportType.SETTLEMENT) {
            clauses.add("d.txn_at >= :transactionFrom");
            params.addValue("transactionFrom", query.transactionFrom().atStartOfDay());
        }
        if (query.transactionTo() != null && type == WfsReportType.SETTLEMENT) {
            clauses.add("d.txn_at < :transactionTo");
            params.addValue("transactionTo", query.transactionTo().plusDays(1).atStartOfDay());
        }
        if (notBlank(query.type()) && type == WfsReportType.SETTLEMENT) {
            clauses.add("d.transaction_type = :transactionType");
            params.addValue("transactionType", query.type().strip());
        }
        if (notBlank(query.reason()) && type == WfsReportType.SETTLEMENT) {
            clauses.add("d.reason_code = :reason");
            params.addValue("reason", query.reason().strip());
        }
        if (notBlank(query.match())) {
            clauses.add("d.match_status = :matchStatus");
            params.addValue("matchStatus", query.match().strip().toUpperCase(Locale.ROOT));
        }
        if (query.feeMin() != null) {
            clauses.add(type == WfsReportType.STORAGE ? "d.final_storage_fee >= :feeMin" : "d.net_payable >= :feeMin");
            params.addValue("feeMin", query.feeMin());
        }
        if (query.feeMax() != null) {
            clauses.add(type == WfsReportType.STORAGE ? "d.final_storage_fee <= :feeMax" : "d.net_payable <= :feeMax");
            params.addValue("feeMax", query.feeMax());
        }
        if (notBlank(query.search())) {
            params.addValue("search", "%" + query.search().strip() + "%");
            if (type == WfsReportType.SETTLEMENT) {
                clauses.add("(d.order_id LIKE :search OR d.po_no LIKE :search OR d.sku LIKE :search "
                        + "OR d.parent_sku LIKE :search OR d.partner_gtin LIKE :search OR d.wfs_reference_id LIKE :search "
                        + "OR d.partner_item_name LIKE :search)");
            } else {
                clauses.add("(d.sku LIKE :search OR d.parent_sku LIKE :search OR d.gtin LIKE :search "
                        + "OR d.item_id LIKE :search OR d.item_name LIKE :search)");
            }
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private Map<String, Object> summary(WfsReportType type, String where, MapSqlParameterSource params) {
        Map<String, Object> result = new LinkedHashMap<>();
        String table = dataTable(type);
        String sql = type == WfsReportType.SETTLEMENT
                ? "SELECT COUNT(*) row_count, COALESCE(SUM(net_payable),0) total_amount, "
                + "COALESCE(SUM(CASE WHEN net_payable > 0 THEN net_payable ELSE 0 END),0) positive_amount, "
                + "COALESCE(SUM(CASE WHEN net_payable < 0 THEN net_payable ELSE 0 END),0) negative_amount, "
                + "COALESCE(SUM(qty),0) total_quantity FROM " + table + " d " + where
                : "SELECT COUNT(*) row_count, COALESCE(SUM(final_storage_fee),0) total_amount, "
                + "COALESCE(SUM(std_avg_units),0) standard_units, "
                + "COALESCE(SUM(lt_avg_units),0) long_term_units, "
                + "COALESCE(SUM(lt2_avg_units),0) over_450_units FROM " + table + " d " + where;
        result.putAll(jdbc.queryForMap(sql, params));
        if (type == WfsReportType.SETTLEMENT) {
            List<Map<String, Object>> breakdown = jdbc.queryForList("SELECT transaction_type, reason_code, COUNT(*) row_count, "
                    + "COALESCE(SUM(net_payable),0) amount FROM " + table + " d " + where
                    + " GROUP BY transaction_type, reason_code ORDER BY ABS(amount) DESC, row_count DESC", params);
            result.put("breakdown", breakdown);
        }
        return result;
    }

    private Period resolvePeriod(WfsReportType type, LocalDate start, LocalDate end) {
        if (start != null || end != null) {
            if (start == null || end == null) throw new IllegalArgumentException("报表周期开始和结束日期必须同时选择");
            return new Period(start, end);
        }
        List<Period> latest = jdbc.query("SELECT period_start, period_end FROM " + dataTable(type)
                        + " GROUP BY period_start, period_end ORDER BY period_end DESC, period_start DESC LIMIT 1",
                (rs, rowNum) -> new Period(rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate()));
        return latest.isEmpty() ? null : latest.getFirst();
    }

    private List<Period> periods(WfsReportType type) {
        return jdbc.query("SELECT period_start, period_end FROM " + dataTable(type)
                        + " GROUP BY period_start, period_end ORDER BY period_end DESC, period_start DESC",
                (rs, rowNum) -> new Period(rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate()));
    }

    private Map<String, Object> officialStorageSummary(Period period) {
        List<String> values = jdbc.queryForList("""
                SELECT summary_json FROM wm_ads_wfs_import_batch
                 WHERE report_type = 'STORAGE' AND status <> 'DELETED'
                   AND period_start = :start AND period_end = :end AND summary_json IS NOT NULL
                 ORDER BY imported_at DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("start", period.start()).addValue("end", period.end()), String.class);
        return values.isEmpty() ? Map.of() : readJson(values.getFirst());
    }

    private String selectSql(WfsReportType type) {
        if (type == WfsReportType.SETTLEMENT) {
            return "SELECT d.id, d.period_start periodStart, d.period_end periodEnd, d.txn_at transactionAt, "
                    + "d.transaction_type transactionType, d.reason_code reasonCode, d.order_id orderId, "
                    + "d.order_line orderLine, d.sku, d.parent_sku parentSku, d.partner_gtin partnerGtin, "
                    + "d.partner_item_name partnerItemName, d.qty quantity, d.net_payable netPayable, "
                    + "d.wfs_reference_id wfsReferenceId, d.match_status matchStatus FROM wm_ads_wfs_fulfillment_fee d";
        }
        return "SELECT d.id, d.period_start periodStart, d.period_end periodEnd, d.sku, "
                + "d.parent_sku parentSku, d.gtin, d.item_id itemId, d.item_name itemName, "
                + "d.std_avg_units standardAverageUnits, d.lt_avg_units longTermAverageUnits, "
                + "d.lt2_avg_units over450AverageUnits, d.days_in_period daysInPeriod, "
                + "d.final_storage_fee finalStorageFee, d.match_status matchStatus FROM wm_ads_wfs_storage_fee d";
    }

    private String sortColumn(WfsReportType type, String sort) {
        Map<String, String> allowed = type == WfsReportType.SETTLEMENT
                ? Map.of("transactionAt", "d.txn_at", "amount", "d.net_payable", "quantity", "d.qty",
                "sku", "d.sku", "type", "d.transaction_type")
                : Map.of("fee", "d.final_storage_fee", "sku", "d.sku", "item", "d.item_id",
                "standardUnits", "d.std_avg_units", "longTermUnits", "d.lt_avg_units");
        String fallback = type == WfsReportType.SETTLEMENT ? "d.txn_at" : "d.final_storage_fee";
        return sort == null ? fallback : allowed.getOrDefault(sort, fallback);
    }

    private PreparedRow prepare(WfsReportType type, ParsedRow source, ProductIndex products,
                                String fileName, String sourceStore) {
        if (type == WfsReportType.STORAGE) {
            StorageRow row = (StorageRow) source;
            ProductMatch match = products.forStorage(row.sku(), row.gtin());
            String hash = storageHash(row);
            return new PreparedRow(hash, storageParams(row, match, fileName, sourceStore, hash), row.finalStorageFee());
        }
        SettlementRow row = (SettlementRow) source;
        ProductMatch match = products.forGtin(row.partnerGtin());
        String hash = settlementHash(row);
        return new PreparedRow(hash, settlementParams(row, match, fileName, sourceStore, hash), row.netPayable());
    }

    private MapSqlParameterSource storageParams(StorageRow row, ProductMatch match, String fileName,
                                                String sourceStore, String hash) {
        return new MapSqlParameterSource()
                .addValue("periodStart", row.periodStart()).addValue("periodEnd", row.periodEnd())
                .addValue("periodLabel", row.periodEnd().toString().substring(0, 7))
                .addValue("gtin", truncate(row.gtin(), 32)).addValue("sku", truncate(row.sku(), 180))
                .addValue("parentSku", truncate(match.parentSku(), 80)).addValue("matchStatus", match.status())
                .addValue("itemId", truncate(row.itemId(), 80)).addValue("itemName", truncate(row.itemName(), 600))
                .addValue("lengthIn", row.lengthIn()).addValue("widthIn", row.widthIn())
                .addValue("heightIn", row.heightIn()).addValue("weightLb", row.weightLb())
                .addValue("standardDailyFee", row.standardDailyFee()).addValue("peakDailyFee", row.peakDailyFee())
                .addValue("longTermDailyFee", row.longTermDailyFee())
                .addValue("longTermOver450DailyFee", row.longTermOver450DailyFee())
                .addValue("standardAverageUnits", row.standardAverageUnits())
                .addValue("peakAverageUnits", row.peakAverageUnits())
                .addValue("longTermAverageUnits", row.longTermAverageUnits())
                .addValue("longTermOver450AverageUnits", row.longTermOver450AverageUnits())
                .addValue("daysInPeriod", row.daysInPeriod()).addValue("finalStorageFee", row.finalStorageFee())
                .addValue("originalAmount", row.originalAmount()).addValue("discountSavings", row.discountSavings())
                .addValue("fileName", fileName).addValue("sourceStore", truncate(sourceStore, 160))
                .addValue("rowHash", hash).addValue("rawFields", json(row.rawFields()));
    }

    private MapSqlParameterSource settlementParams(SettlementRow row, ProductMatch match, String fileName,
                                                   String sourceStore, String hash) {
        return new MapSqlParameterSource()
                .addValue("periodStart", row.periodStart()).addValue("periodEnd", row.periodEnd())
                .addValue("periodLabel", row.periodEnd().toString().substring(0, 7))
                .addValue("orderId", truncate(row.orderId(), 80)).addValue("orderLine", truncate(row.orderLine(), 40))
                .addValue("poNumber", truncate(row.poNumber(), 80)).addValue("poLine", truncate(row.poLine(), 40))
                .addValue("transactionType", truncate(row.transactionType(), 80))
                .addValue("reasonCode", truncate(row.reasonCode(), 200)).addValue("detail", truncate(row.detail(), 200))
                .addValue("wfsReferenceId", truncate(row.wfsReferenceId(), 300))
                .addValue("transactionAt", row.transactionAt())
                .addValue("transactionDate", row.transactionAt() == null ? null : row.transactionAt().toLocalDate())
                .addValue("quantity", row.quantity()).addValue("billingMethod", truncate(row.billingMethod(), 60))
                .addValue("partnerId", truncate(row.partnerId(), 80)).addValue("partnerGtin", truncate(row.partnerGtin(), 32))
                .addValue("sku", truncate(match.sku(), 180)).addValue("parentSku", truncate(match.parentSku(), 80))
                .addValue("matchStatus", match.status()).addValue("partnerItemName", truncate(row.partnerItemName(), 600))
                .addValue("category", truncate(row.category(), 200)).addValue("subCategory", truncate(row.subCategory(), 200))
                .addValue("netPayable", row.netPayable()).addValue("originalAmount", row.originalAmount())
                .addValue("discountSavings", row.discountSavings())
                .addValue("customerOrderId", truncate(row.customerOrderId(), 80))
                .addValue("orderChannelId", truncate(row.orderChannelId(), 80))
                .addValue("fileName", fileName).addValue("sourceStore", truncate(sourceStore, 160))
                .addValue("rowHash", hash).addValue("rawFields", json(row.rawFields()));
    }

    private void insertRows(WfsReportType type, List<PreparedRow> rows) {
        if (rows.isEmpty()) return;
        String sql = type == WfsReportType.STORAGE ? """
                INSERT IGNORE INTO wm_ads_wfs_storage_fee
                (period_start, period_end, period_label, gtin, sku, parent_sku, match_status, item_id, item_name,
                 length_in, width_in, height_in, weight_lb, std_daily_fee, peak_daily_fee, lt_daily_fee, lt2_daily_fee,
                 std_avg_units, peak_avg_units, lt_avg_units, lt2_avg_units, days_in_period, final_storage_fee,
                 original_amount, discount_savings, source_file, source_store, row_hash, raw_fields, managed_by_batches)
                VALUES (:periodStart, :periodEnd, :periodLabel, :gtin, :sku, :parentSku, :matchStatus, :itemId, :itemName,
                 :lengthIn, :widthIn, :heightIn, :weightLb, :standardDailyFee, :peakDailyFee, :longTermDailyFee,
                 :longTermOver450DailyFee, :standardAverageUnits, :peakAverageUnits, :longTermAverageUnits,
                 :longTermOver450AverageUnits, :daysInPeriod, :finalStorageFee, :originalAmount, :discountSavings,
                 :fileName, :sourceStore, :rowHash, :rawFields, 1)
                """ : """
                INSERT IGNORE INTO wm_ads_wfs_fulfillment_fee
                (period_start, period_end, period_label, order_id, order_line, po_no, po_line, transaction_type,
                 reason_code, detail, wfs_reference_id, txn_date, txn_at, qty, billing_method, partner_id, partner_gtin,
                 sku, parent_sku, match_status, partner_item_name, category, sub_category, net_payable,
                 original_amount, discount_savings, customer_order_id, order_channel_id, source_file, source_store,
                 row_hash, raw_fields, managed_by_batches)
                VALUES (:periodStart, :periodEnd, :periodLabel, :orderId, :orderLine, :poNumber, :poLine,
                 :transactionType, :reasonCode, :detail, :wfsReferenceId, :transactionDate, :transactionAt, :quantity,
                 :billingMethod, :partnerId, :partnerGtin, :sku, :parentSku, :matchStatus, :partnerItemName, :category,
                 :subCategory, :netPayable, :originalAmount, :discountSavings, :customerOrderId, :orderChannelId,
                 :fileName, :sourceStore, :rowHash, :rawFields, 1)
                """;
        SqlParameterSource[] batch = rows.stream().map(PreparedRow::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    private Map<String, Long> findRowIds(WfsReportType type, Collection<String> hashes) {
        if (hashes.isEmpty()) return Map.of();
        Map<String, Long> result = new HashMap<>();
        for (List<String> chunk : chunks(new ArrayList<>(hashes), HASH_CHUNK_SIZE)) {
            jdbc.queryForList("SELECT id, row_hash FROM " + dataTable(type) + " WHERE row_hash IN (:hashes)",
                            new MapSqlParameterSource("hashes", chunk))
                    .forEach(row -> result.put(String.valueOf(row.get("row_hash")), ((Number) row.get("id")).longValue()));
        }
        return result;
    }

    private void insertBatchLinks(long batchId, WfsReportType type, Map<String, Long> rowIds,
                                  Set<String> preexisting) {
        SqlParameterSource[] links = rowIds.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("batchId", batchId).addValue("type", type.name()).addValue("rowId", entry.getValue())
                        .addValue("duplicate", preexisting.contains(entry.getKey())))
                .toArray(SqlParameterSource[]::new);
        if (links.length > 0) jdbc.batchUpdate("""
                INSERT IGNORE INTO wm_ads_wfs_batch_row (batch_id, report_type, row_id, duplicate_on_import)
                VALUES (:batchId, :type, :rowId, :duplicate)
                """, links);
    }

    private long insertBatch(WfsReportType type, ParsedReport report, String fileName, String fileHash,
                             long fileSize, String status, int totalRows, int importedRows, int duplicateRows,
                             int failedRows, int linkedRows, Map<String, Object> summary, List<RowError> errors,
                             String createdBy, String errorMessage) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("type", type.name()).addValue("sourceStore", truncate(report.sourceStore(), 160))
                .addValue("fileName", fileName).addValue("fileHash", fileHash).addValue("activeHash", fileHash)
                .addValue("fileSize", fileSize).addValue("periodStart", report.periodStart())
                .addValue("periodEnd", report.periodEnd()).addValue("status", status).addValue("totalRows", totalRows)
                .addValue("importedRows", importedRows).addValue("duplicateRows", duplicateRows)
                .addValue("failedRows", failedRows).addValue("linkedRows", linkedRows)
                .addValue("summary", json(summary)).addValue("errors", json(errors.stream().limit(200).toList()))
                .addValue("errorMessage", truncate(errorMessage, 1000)).addValue("createdBy", truncate(createdBy, 64));
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO wm_ads_wfs_import_batch
                (report_type, source_store, file_name, file_sha256, active_file_hash, file_size, period_start,
                 period_end, status, total_rows, imported_rows, duplicate_rows, failed_rows, linked_rows,
                 summary_json, error_details, error_message, created_by)
                VALUES (:type, :sourceStore, :fileName, :fileHash, :activeHash, :fileSize, :periodStart,
                 :periodEnd, :status, :totalRows, :importedRows, :duplicateRows, :failedRows, :linkedRows,
                 :summary, :errors, :errorMessage, :createdBy)
                """, params, key, new String[]{"id"});
        if (key.getKey() == null) throw new IllegalStateException("WFS 导入批次创建失败");
        return key.getKey().longValue();
    }

    private long insertFailedBatch(WfsReportType type, String fileName, String hash, long size,
                                   String createdBy, String message) {
        ParsedReport empty = new ParsedReport(type, null, null, null, Map.of(), List.of(), List.of());
        return insertBatch(type, empty, fileName, hash, size, "FAILED", 0, 0, 0, 0, 0,
                Map.of(), List.of(), createdBy, message);
    }

    private ImportResult findActiveFile(WfsReportType type, String hash) {
        List<ImportResult> values = jdbc.query("""
                SELECT id, status, file_name, period_start, period_end, total_rows, imported_rows,
                       duplicate_rows, failed_rows
                  FROM wm_ads_wfs_import_batch
                 WHERE report_type = :type AND active_file_hash = :hash AND status <> 'DELETED'
                 LIMIT 1
                """, new MapSqlParameterSource().addValue("type", type.name()).addValue("hash", hash),
                (rs, rowNum) -> new ImportResult(rs.getLong("id"), rs.getString("status"), rs.getString("file_name"),
                        date(rs.getObject("period_start")), date(rs.getObject("period_end")), rs.getInt("total_rows"),
                        rs.getInt("imported_rows"), rs.getInt("duplicate_rows"), rs.getInt("failed_rows"), true,
                        "该文件已经导入，本次未重复处理", List.of()));
        return values.isEmpty() ? null : values.getFirst();
    }

    private ProductIndex productIndex() {
        List<ProductRow> rows = jdbc.query("""
                SELECT sku, parent_sku, gtin, upc, product_id
                  FROM wm_ads_product_record
                 WHERE current_version = 1
                """, (rs, rowNum) -> new ProductRow(rs.getString("sku"), rs.getString("parent_sku"),
                rs.getString("gtin"), rs.getString("upc"), rs.getString("product_id")));
        return new ProductIndex(rows);
    }

    private String storageHash(StorageRow row) {
        return sha256(join("STORAGE", row.periodStart(), row.periodEnd(), row.gtin(), row.sku(), row.itemId(), row.itemName(),
                scale(row.lengthIn(), 4), scale(row.widthIn(), 4), scale(row.heightIn(), 4), scale(row.weightLb(), 6),
                scale(row.standardDailyFee(), 8), scale(row.peakDailyFee(), 8), scale(row.longTermDailyFee(), 8),
                scale(row.longTermOver450DailyFee(), 8), scale(row.standardAverageUnits(), 4),
                scale(row.peakAverageUnits(), 4), scale(row.longTermAverageUnits(), 4),
                scale(row.longTermOver450AverageUnits(), 4), row.daysInPeriod(), scale(row.finalStorageFee(), 8),
                scale(row.originalAmount(), 8), scale(row.discountSavings(), 8)).getBytes(StandardCharsets.UTF_8));
    }

    private String settlementHash(SettlementRow row) {
        if (notBlank(row.wfsReferenceId())) return sha256(("REF" + SEP + row.wfsReferenceId().strip()).getBytes(StandardCharsets.UTF_8));
        return sha256(join("SETTLEMENT", row.orderId(), row.orderLine(), row.poNumber(), row.poLine(),
                row.transactionType(), row.reasonCode(), row.detail(),
                row.transactionAt() == null ? null : row.transactionAt().format(SQL_DATE_TIME), scale(row.quantity(), 4),
                row.billingMethod(), row.partnerId(), row.partnerGtin(), row.partnerItemName(), row.category(),
                row.subCategory(), scale(row.netPayable(), 8), row.customerOrderId(), row.orderChannelId(),
                scale(row.originalAmount(), 8), scale(row.discountSavings(), 8)).getBytes(StandardCharsets.UTF_8));
    }

    private static String join(Object... values) {
        return java.util.Arrays.stream(values).map(value -> value == null ? "" : value.toString())
                .collect(Collectors.joining(SEP));
    }

    private static String scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    private static String dataTable(WfsReportType type) {
        return type == WfsReportType.SETTLEMENT ? "wm_ads_wfs_fulfillment_fee" : "wm_ads_wfs_storage_fee";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("WFS 原始字段无法保存", exception);
        }
    }

    private Map<String, Object> readJson(Object value) {
        if (value == null || value.toString().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value.toString(), OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            return Map.of("raw", value.toString());
        }
    }

    private List<Map<String, Object>> readJsonList(Object value) {
        if (value == null || value.toString().isBlank()) return List.of();
        try {
            return objectMapper.readValue(value.toString(), new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of(Map.of("message", value.toString()));
        }
    }

    private static BigDecimal decimalSummary(Map<String, Object> summary, String normalizedKey) {
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            if (entry.getKey().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").equals(normalizedKey)) {
                if (entry.getValue() instanceof BigDecimal value) return value;
                if (entry.getValue() instanceof Number value) return new BigDecimal(value.toString());
            }
        }
        return null;
    }

    private static String importMessage(int imported, int duplicates, int failed) {
        return "导入完成：新增 " + imported + " 行，跳过重复 " + duplicates + " 行，失败 " + failed + " 行";
    }

    private static void validateFile(String fileName, byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("请选择非空文件");
        if (content.length > MAX_FILE_BYTES) throw new IllegalArgumentException("文件不能超过 100MB");
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .csv 或 .xlsx 文件");
        }
    }

    private static String safeFileName(String original) {
        String normalized = original == null ? "wfs-upload.csv" : original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        return truncate(name.isBlank() ? "wfs-upload.csv" : name, 300);
    }

    private static String truncate(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        return LocalDate.parse(value.toString());
    }

    private static <T> List<List<T>> chunks(List<T> source, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index += size) {
            result.add(source.subList(index, Math.min(source.size(), index + size)));
        }
        return result;
    }

    private record PreparedRow(String hash, MapSqlParameterSource params, BigDecimal amount) {
    }

    private record ProductRow(String sku, String parentSku, String gtin, String upc, String productId) {
    }

    private record ProductMatch(String sku, String parentSku, String status) {
    }

    private static final class ProductIndex {
        private final Map<String, ProductRow> bySku = new HashMap<>();
        private final Map<String, ProductRow> byGtin = new HashMap<>();
        private final Map<String, ProductRow> byParent = new HashMap<>();

        private ProductIndex(List<ProductRow> rows) {
            for (ProductRow row : rows) {
                if (notBlank(row.sku())) bySku.putIfAbsent(row.sku().strip().toUpperCase(Locale.ROOT), row);
                if (notBlank(row.parentSku())) byParent.putIfAbsent(row.parentSku().strip().toUpperCase(Locale.ROOT), row);
                for (String value : List.of(nullToEmpty(row.gtin()), nullToEmpty(row.upc()), nullToEmpty(row.productId()))) {
                    String normalized = normalizeGtin(value);
                    if (normalized != null) byGtin.putIfAbsent(normalized, row);
                }
            }
        }

        private ProductMatch forStorage(String sku, String gtin) {
            String original = nullToEmpty(sku).strip();
            String withoutWfs = original.replaceFirst("(?i)-WFS\\d*$", "");
            ProductRow row = bySku.get(original.toUpperCase(Locale.ROOT));
            if (row == null) row = bySku.get(withoutWfs.toUpperCase(Locale.ROOT));
            if (row == null) row = byGtin.get(normalizeGtin(gtin));
            String derivedParent = parentOf(withoutWfs);
            if (row == null) row = byParent.get(nullToEmpty(derivedParent).toUpperCase(Locale.ROOT));
            return row == null
                    ? new ProductMatch(original, derivedParent, "UNMATCHED")
                    : new ProductMatch(notBlank(row.sku()) ? row.sku() : original,
                    notBlank(row.parentSku()) ? row.parentSku() : derivedParent, "MATCHED");
        }

        private ProductMatch forGtin(String gtin) {
            ProductRow row = byGtin.get(normalizeGtin(gtin));
            return row == null ? new ProductMatch(null, null, "UNMATCHED")
                    : new ProductMatch(row.sku(), notBlank(row.parentSku()) ? row.parentSku() : parentOf(row.sku()), "MATCHED");
        }

        private static String normalizeGtin(String value) {
            if (!notBlank(value)) return null;
            String clean = value.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
            return clean.isBlank() ? null : clean;
        }

        private static String parentOf(String sku) {
            if (!notBlank(sku)) return null;
            int dash = sku.indexOf('-');
            return (dash < 0 ? sku : sku.substring(0, dash)).strip();
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    public record Query(LocalDate periodStart, LocalDate periodEnd, LocalDate transactionFrom,
                        LocalDate transactionTo, String type, String reason, String match,
                        BigDecimal feeMin, BigDecimal feeMax, String search, int page, int size,
                        String sort, String direction) {
        public static Query empty() {
            return new Query(null, null, null, null, null, null, null, null, null,
                    null, 0, 50, null, "DESC");
        }

        public Query normalized() {
            int safeSize = Set.of(20, 50, 100).contains(size) ? size : 50;
            String safeDirection = "ASC".equalsIgnoreCase(direction) ? "ASC" : "DESC";
            return new Query(periodStart, periodEnd, transactionFrom, transactionTo, type, reason, match,
                    feeMin, feeMax, search, Math.max(0, page), safeSize, sort, safeDirection);
        }
    }

    public record Period(LocalDate start, LocalDate end) {
    }

    public record PageResult(List<Map<String, Object>> rows, long total, int page, int size,
                             Period appliedPeriod, List<Period> periods, Map<String, Object> summary,
                             Map<String, Object> officialSummary) {
    }

    public record ImportResult(long batchId, String status, String fileName, LocalDate periodStart,
                               LocalDate periodEnd, int totalRows, int importedRows, int duplicateRows,
                               int failedRows, boolean alreadyImported, String message, List<RowError> errors) {
        private ImportResult withAlreadyImported(boolean value) {
            return new ImportResult(batchId, status, fileName, periodStart, periodEnd, totalRows, importedRows,
                    duplicateRows, failedRows, value, message, errors);
        }
    }

    public record BatchView(long id, WfsReportType type, String sourceStore, String fileName, String fileHash,
                            long fileSize, LocalDate periodStart, LocalDate periodEnd, String status,
                            int totalRows, int importedRows, int duplicateRows, int failedRows, int linkedRows,
                            Map<String, Object> summary, List<Map<String, Object>> errors, String errorMessage,
                            String createdBy, LocalDateTime importedAt, LocalDateTime deletedAt) {
    }

    public record DeleteResult(long batchId, int removedLinks, int removedRows) {
    }
}
