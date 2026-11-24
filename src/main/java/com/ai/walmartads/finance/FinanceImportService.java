package com.ai.walmartads.finance;

import com.ai.walmartads.finance.FinanceModels.ParsedFinanceWorkbook;
import com.ai.walmartads.finance.FinanceModels.StoreMonthlyRow;
import com.ai.walmartads.finance.FinanceModels.StyleMonthlyRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Imports PNK monthly finance profit workbooks directly into MySQL (workspace-scoped).
 */
@Service
public class FinanceImportService {

    private final JdbcTemplate jdbc;
    private final FinanceWorkbookParser parser;
    private final TransactionTemplate tx;

    public FinanceImportService(
            JdbcTemplate jdbc,
            FinanceWorkbookParser parser,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record DirectoryImportResult(
            long workspaceId,
            String accountCode,
            int filesScanned,
            int filesImported,
            int filesSkipped,
            int storeMonthsTotal,
            int styleMonthsTotal,
            int styleRowsTotal,
            List<String> missingStyleMonths,
            List<FileImportSummary> files
    ) {
    }

    public record FileImportSummary(
            String fileName,
            String templateEra,
            String fileReportMonth,
            int storeMonths,
            int styleMonths,
            int styleRows
    ) {
    }

    /**
     * Imports top-level {@code .xlsx} files from {@code directory} (ignoring subdirectories)
     * sorted strictly chronologically by (year, month) so newer workbooks supersede older snapshots
     * while preserving valid historical style details.
     */
    public DirectoryImportResult importDirectory(Path directory, long workspaceId) throws Exception {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("财务目录不存在：" + directory);
        }
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(directory)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> FinanceWorkbookParser.isPnkProfitCandidate(p.getFileName().toString()))
                    .sorted(Comparator.comparing(
                                    (Path p) -> FinanceWorkbookParser.extractFileYearMonth(p.getFileName().toString()).orElseThrow())
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        }

        List<ParsedFinanceWorkbook> parsedWorkbooks = new ArrayList<>();
        int skipped = 0;
        for (Path file : candidates) {
            Optional<ParsedFinanceWorkbook> parsed = parser.parsePnkWorkbook(file);
            if (parsed.isPresent()) {
                parsedWorkbooks.add(parsed.get());
            } else {
                skipped++;
            }
        }

        final int filesSkipped = skipped;
        return tx.execute(status -> {
            List<FileImportSummary> summaries = new ArrayList<>();
            for (ParsedFinanceWorkbook wb : parsedWorkbooks) {
                long batchId = upsertBatch(workspaceId, wb);
                for (StoreMonthlyRow storeRow : wb.storeMonths()) {
                    upsertStoreMonthly(workspaceId, batchId, storeRow);
                }
                int styleRowCount = 0;
                for (Map.Entry<String, List<StyleMonthlyRow>> entry : wb.styleMonths().entrySet()) {
                    String month = entry.getKey();
                    List<StyleMonthlyRow> rows = entry.getValue();
                    if (rows.isEmpty()) continue;
                    // Replace that month's PNK styles atomically with the valid rows from this file
                    jdbc.update("""
                            delete from wm_ads_finance_style_monthly
                             where workspace_id = ? and account_code = ? and report_month = ?
                            """, workspaceId, wb.accountCode(), month);
                    for (StyleMonthlyRow styleRow : rows) {
                        insertStyleMonthly(workspaceId, batchId, styleRow);
                        styleRowCount++;
                    }
                }
                summaries.add(new FileImportSummary(
                        wb.fileName(),
                        wb.templateEra(),
                        wb.fileReportMonth(),
                        wb.storeMonths().size(),
                        wb.styleMonths().size(),
                        styleRowCount
                ));
            }

            reconcileStoreAndStyleFlags(workspaceId, FinanceWorkbookParser.TARGET_ACCOUNT_PNK);

            Integer storeMonthsTotal = jdbc.queryForObject(
                    "select count(*) from wm_ads_finance_store_monthly where workspace_id = ? and account_code = ?",
                    Integer.class, workspaceId, FinanceWorkbookParser.TARGET_ACCOUNT_PNK);
            Integer styleMonthsTotal = jdbc.queryForObject(
                    "select count(distinct report_month) from wm_ads_finance_style_monthly where workspace_id = ? and account_code = ?",
                    Integer.class, workspaceId, FinanceWorkbookParser.TARGET_ACCOUNT_PNK);
            Integer styleRowsTotal = jdbc.queryForObject(
                    "select count(*) from wm_ads_finance_style_monthly where workspace_id = ? and account_code = ?",
                    Integer.class, workspaceId, FinanceWorkbookParser.TARGET_ACCOUNT_PNK);
            List<String> missingStyleMonths = jdbc.queryForList("""
                    select report_month
                      from wm_ads_finance_store_monthly
                     where workspace_id = ? and account_code = ? and has_style_detail = false
                     order by report_month
                    """, String.class, workspaceId, FinanceWorkbookParser.TARGET_ACCOUNT_PNK);

            return new DirectoryImportResult(
                    workspaceId,
                    FinanceWorkbookParser.TARGET_ACCOUNT_PNK,
                    candidates.size(),
                    parsedWorkbooks.size(),
                    filesSkipped,
                    storeMonthsTotal == null ? 0 : storeMonthsTotal,
                    styleMonthsTotal == null ? 0 : styleMonthsTotal,
                    styleRowsTotal == null ? 0 : styleRowsTotal,
                    missingStyleMonths,
                    summaries
            );
        });
    }

    private long upsertBatch(long workspaceId, ParsedFinanceWorkbook wb) {
        int styleRows = wb.styleMonths().values().stream().mapToInt(List::size).sum();
        List<Long> existing = jdbc.queryForList(
                "select id from wm_ads_finance_import_batch where workspace_id = ? and file_sha256 = ?",
                Long.class, workspaceId, wb.fileSha256());
        if (!existing.isEmpty()) {
            long id = existing.getFirst();
            jdbc.update("""
                    update wm_ads_finance_import_batch
                       set file_name = ?, template_era = ?, file_report_month = ?,
                           store_months_imported = ?, style_months_imported = ?, style_rows_imported = ?,
                           imported_at = current_timestamp(6)
                     where id = ? and workspace_id = ?
                    """, wb.fileName(), wb.templateEra(), wb.fileReportMonth(),
                    wb.storeMonths().size(), wb.styleMonths().size(), styleRows, id, workspaceId);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into wm_ads_finance_import_batch
                        (workspace_id, account_code, file_name, file_sha256, template_era, file_report_month,
                         store_months_imported, style_months_imported, style_rows_imported)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, workspaceId);
            ps.setString(2, wb.accountCode());
            ps.setString(3, wb.fileName());
            ps.setString(4, wb.fileSha256());
            ps.setString(5, wb.templateEra());
            ps.setString(6, wb.fileReportMonth());
            ps.setInt(7, wb.storeMonths().size());
            ps.setInt(8, wb.styleMonths().size());
            ps.setInt(9, styleRows);
            return ps;
        }, keyHolder);
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number num) {
            return num.longValue();
        }
        return keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
    }

    private void upsertStoreMonthly(long workspaceId, long batchId, StoreMonthlyRow row) {
        jdbc.update("""
                insert into wm_ads_finance_store_monthly (
                    workspace_id, account_code, company_name, report_year, report_month, settlement_period,
                    exchange_rate, sales_revenue_usd, refund_usd, commission_usd, wfs_fee_usd, ad_spend_usd,
                    storage_fee_usd, inventory_reimbursement_usd, inventory_disposal_usd, promo_discount_usd,
                    other_platform_fee_usd, net_settlement_usd, settlement_ratio, net_settlement_cny,
                    purchase_cost_cny, first_leg_freight_cny, offsite_ad_cny, offsite_fee_cny,
                    gross_profit_cny, profit_margin, sales_qty, refund_qty, return_qty, return_sellable_qty,
                    source_file_name, source_batch_id
                ) values (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?
                )
                on duplicate key update
                    company_name = coalesce(values(company_name), company_name),
                    settlement_period = coalesce(nullif(values(settlement_period), ''), settlement_period),
                    exchange_rate = values(exchange_rate),
                    sales_revenue_usd = values(sales_revenue_usd),
                    refund_usd = values(refund_usd),
                    commission_usd = values(commission_usd),
                    wfs_fee_usd = values(wfs_fee_usd),
                    ad_spend_usd = values(ad_spend_usd),
                    storage_fee_usd = values(storage_fee_usd),
                    inventory_reimbursement_usd = values(inventory_reimbursement_usd),
                    inventory_disposal_usd = values(inventory_disposal_usd),
                    promo_discount_usd = values(promo_discount_usd),
                    other_platform_fee_usd = values(other_platform_fee_usd),
                    net_settlement_usd = values(net_settlement_usd),
                    settlement_ratio = values(settlement_ratio),
                    net_settlement_cny = values(net_settlement_cny),
                    purchase_cost_cny = values(purchase_cost_cny),
                    first_leg_freight_cny = values(first_leg_freight_cny),
                    offsite_ad_cny = values(offsite_ad_cny),
                    offsite_fee_cny = values(offsite_fee_cny),
                    gross_profit_cny = values(gross_profit_cny),
                    profit_margin = values(profit_margin),
                    sales_qty = values(sales_qty),
                    refund_qty = values(refund_qty),
                    return_qty = values(return_qty),
                    return_sellable_qty = coalesce(values(return_sellable_qty), return_sellable_qty),
                    source_file_name = values(source_file_name),
                    source_batch_id = values(source_batch_id),
                    updated_at = current_timestamp(6)
                """,
                workspaceId, row.accountCode(), row.companyName(), row.reportYear(), row.reportMonth(),
                row.settlementPeriod(), row.exchangeRate(), row.salesRevenueUsd(), row.refundUsd(),
                row.commissionUsd(), row.wfsFeeUsd(), row.adSpendUsd(), row.storageFeeUsd(),
                row.inventoryReimbursementUsd(), row.inventoryDisposalUsd(), row.promoDiscountUsd(),
                row.otherPlatformFeeUsd(), row.netSettlementUsd(), row.settlementRatio(),
                row.netSettlementCny(), row.purchaseCostCny(), row.firstLegFreightCny(),
                row.offsiteAdCny(), row.offsiteFeeCny(), row.grossProfitCny(), row.profitMargin(),
                row.salesQty(), row.refundQty(), row.returnQty(), row.returnSellableQty(),
                row.sourceFileName(), batchId);
    }

    private void insertStyleMonthly(long workspaceId, long batchId, StyleMonthlyRow row) {
        jdbc.update("""
                insert into wm_ads_finance_style_monthly (
                    workspace_id, account_code, company_name, country, report_year, report_month,
                    parent_sku, listing_status, season, category, owner_name,
                    exchange_rate, avg_selling_price_usd, sales_qty, sales_revenue_usd,
                    refund_qty, refund_usd, refund_rate, return_qty,
                    wfs_fee_usd, commission_usd, promo_discount_usd, ad_spend_usd, storage_fee_usd,
                    inventory_reimbursement_usd, inventory_disposal_usd, other_platform_fee_usd,
                    net_settlement_usd, net_settlement_cny,
                    purchase_cost_cny, first_leg_cost_cny, offsite_fee_cny, profit_cny, profit_margin,
                    source_file_name, source_batch_id
                ) values (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?
                )
                """,
                workspaceId, row.accountCode(), row.companyName(), row.country(), row.reportYear(), row.reportMonth(),
                row.parentSku(), row.listingStatus(), row.season(), row.category(), row.ownerName(),
                row.exchangeRate(), row.avgSellingPriceUsd(), row.salesQty(), row.salesRevenueUsd(),
                row.refundQty(), row.refundUsd(), row.refundRate(), row.returnQty(),
                row.wfsFeeUsd(), row.commissionUsd(), row.promoDiscountUsd(), row.adSpendUsd(), row.storageFeeUsd(),
                row.inventoryReimbursementUsd(), row.inventoryDisposalUsd(), row.otherPlatformFeeUsd(),
                row.netSettlementUsd(), row.netSettlementCny(),
                row.purchaseCostCny(), row.firstLegCostCny(), row.offsiteFeeCny(), row.profitCny(), row.profitMargin(),
                row.sourceFileName(), batchId);
    }

    private void reconcileStoreAndStyleFlags(long workspaceId, String accountCode) {
        Map<String, BigDecimal> styleProfitByMonth = new LinkedHashMap<>();
        jdbc.query("""
                select report_month, sum(profit_cny) as total_profit
                  from wm_ads_finance_style_monthly
                 where workspace_id = ? and account_code = ?
                 group by report_month
                """, rs -> {
            styleProfitByMonth.put(rs.getString("report_month"), rs.getBigDecimal("total_profit"));
        }, workspaceId, accountCode);

        List<Map<String, Object>> storeMonths = jdbc.queryForList("""
                select id, report_month, gross_profit_cny
                  from wm_ads_finance_store_monthly
                 where workspace_id = ? and account_code = ?
                """, workspaceId, accountCode);

        for (Map<String, Object> row : storeMonths) {
            long id = ((Number) row.get("id")).longValue();
            String month = (String) row.get("report_month");
            BigDecimal storeProfit = (BigDecimal) row.get("gross_profit_cny");
            BigDecimal styleProfit = styleProfitByMonth.get(month);
            boolean hasStyle = styleProfit != null;
            BigDecimal diff = hasStyle ? styleProfit.subtract(storeProfit == null ? BigDecimal.ZERO : storeProfit) : null;
            jdbc.update("""
                    update wm_ads_finance_store_monthly
                       set has_style_detail = ?, style_reconcile_diff_cny = ?
                     where id = ? and workspace_id = ?
                    """, hasStyle, diff, id, workspaceId);
        }
    }
}
