package com.ai.walmartads.finance;

import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.ai.walmartads.workspace.WorkspaceSqlGuard;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceModuleTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void filtersCandidatesAndSortsChronologically() {
        assertThat(FinanceWorkbookParser.isPnkProfitCandidate("2026年10月份沃尔玛利润表新 -斐凡.xlsx")).isTrue();
        assertThat(FinanceWorkbookParser.isPnkProfitCandidate("2024年09月沃尔玛CPY-US利润表模板.xlsx")).isFalse();
        assertThat(FinanceWorkbookParser.isPnkProfitCandidate("2026年4月份PNK沃尔玛广告数据分析报告.xlsx")).isFalse();
        assertThat(FinanceWorkbookParser.isPnkProfitCandidate("沃尔玛下载报表-格式路径2025.07.02.xlsx")).isFalse();
        assertThat(FinanceWorkbookParser.isPnkProfitCandidate("~$2026年10月份沃尔玛利润表新 -斐凡.xlsx")).isFalse();

        var sep = FinanceWorkbookParser.extractFileYearMonth("2026年9月份沃尔玛利润表新 -斐凡.xlsx").orElseThrow();
        var oct = FinanceWorkbookParser.extractFileYearMonth("2026年10月份沃尔玛利润表新 -斐凡.xlsx").orElseThrow();
        assertThat(sep.compareTo(oct)).isLessThan(0);
    }

    @Test
    void scopesAllFinanceTablesInWorkspaceSqlGuard() {
        WorkspaceSqlGuard guard = new WorkspaceSqlGuard();
        WorkspaceContext.set(new WorkspacePrincipal(1L, "PINKSIR", "ACTIVE", "110", "PINKSIR", "PNK", true, 1L));

        assertThat(guard.rewrite("select * from wm_ads_finance_store_monthly order by report_month"))
                .contains("from (select * from wm_ads_finance_store_monthly where workspace_id = 1) as wm_ads_finance_store_monthly");
        assertThat(guard.rewrite("select * from wm_ads_finance_style_monthly where parent_sku = ?"))
                .contains("from (select * from wm_ads_finance_style_monthly where workspace_id = 1) as wm_ads_finance_style_monthly");
        assertThat(guard.rewrite("select * from wm_ads_finance_import_batch"))
                .contains("from (select * from wm_ads_finance_import_batch where workspace_id = 1) as wm_ads_finance_import_batch");
    }

    @Test
    void parsesNormalizesImportsAndQueriesEndToEnd() throws Exception {
        // Create a realistic Era 3 PNK workbook covering 2026-09 (store only) & 2026-10 (store + styles, including return cost reversal)
        Path file = tempDir.resolve("2026年10月份沃尔玛利润表新 -斐凡.xlsx");
        createSampleEra3Workbook(file);

        // Also drop a non-profit file and a CPY file to confirm they are ignored
        Files.writeString(tempDir.resolve("2026年4月份PNK沃尔玛广告数据分析报告.xlsx"), "ignored");
        Files.writeString(tempDir.resolve("2024年09月沃尔玛CPY-US利润表模板.xlsx"), "ignored");

        String jdbcUrl = "jdbc:h2:mem:fin_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        SingleConnectionDataSource ds = new SingleConnectionDataSource(jdbcUrl, "sa", "", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            String ddl = new ClassPathResource("db/migration/V90__finance_profit.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$", "");
            for (String stmt : ddl.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    jdbc.execute(trimmed);
                }
            }

            FinanceImportService importService = new FinanceImportService(
                    jdbc,
                    new FinanceWorkbookParser(),
                    new DataSourceTransactionManager(ds)
            );
            FinanceImportService.DirectoryImportResult result = importService.importDirectory(tempDir, 1L);

            assertThat(result.filesImported()).isEqualTo(1);
            assertThat(result.storeMonthsTotal()).isEqualTo(2);
            assertThat(result.styleMonthsTotal()).isEqualTo(1);
            assertThat(result.styleRowsTotal()).isEqualTo(2);
            assertThat(result.missingStyleMonths()).containsExactly("2026-09");

            // Verify analytics queries
            FinanceAnalyticsService analytics = new FinanceAnalyticsService(jdbc);
            FinanceAnalyticsService.OverviewPayload latest = analytics.overview(null, null);
            assertThat(latest.meta().selectedStartMonth()).isEqualTo("2026-10");
            assertThat(latest.meta().selectedSettlementPeriod()).isEqualTo("9.20-10.17");
            assertThat(latest.kpi().salesRevenueUsd()).isEqualByComparingTo("1000.00");
            assertThat(latest.kpi().purchaseCostCny()).isEqualByComparingTo("1980.00");
            assertThat(latest.kpi().firstLegFreightCny()).isEqualByComparingTo("495.00");
            assertThat(latest.kpi().offsiteFeeCny()).isEqualByComparingTo("100.00");
            assertThat(latest.kpi().grossProfitCny()).isEqualByComparingTo("792.55");
            assertThat(latest.previousKpi()).isNotNull();
            assertThat(latest.previousKpi().salesRevenueUsd()).isEqualByComparingTo("800.00");

            // Check clearance return cost reversal preserved on PNK366073
            assertThat(latest.styles()).hasSize(2);
            var clearance = latest.styles().stream()
                    .filter(s -> s.parentSku().equals("PNK366073"))
                    .findFirst().orElseThrow();
            assertThat(clearance.purchaseCostCny()).isEqualByComparingTo("-20.00");
            assertThat(clearance.firstLegCostCny()).isEqualByComparingTo("-5.00");
            assertThat(clearance.profitCny()).isEqualByComparingTo("25.00");

            // Check missing style month view
            FinanceAnalyticsService.OverviewPayload sepView = analytics.overview("2026-09", "2026-09");
            assertThat(sepView.meta().missingStyleMonthsInRange()).containsExactly("2026-09");
            assertThat(sepView.styles()).isEmpty();

            // Check style detail drawer payload
            FinanceAnalyticsService.StyleDetailPayload detail = analytics.styleDetail("PNK366081");
            assertThat(detail.parentSku()).isEqualTo("PNK366081");
            assertThat(detail.category()).isEqualTo("睡衣套装");
            assertThat(detail.lifetime().profitCny()).isEqualByComparingTo("767.55");
            assertThat(detail.months()).hasSize(1);
        } finally {
            ds.destroy();
        }
    }

    @Test
    void estimatesSubsequentCompletedAndInProgressCyclesFromLiveTables() throws Exception {
        Path file = tempDir.resolve("2026年10月份沃尔玛利润表新 -斐凡.xlsx");
        createSampleEra3Workbook(file);

        String jdbcUrl = "jdbc:h2:mem:fin_est_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        SingleConnectionDataSource ds = new SingleConnectionDataSource(jdbcUrl, "sa", "", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            String ddl = new ClassPathResource("db/migration/V90__finance_profit.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$", "");
            for (String stmt : ddl.split(";")) {
                if (!stmt.trim().isEmpty()) jdbc.execute(stmt.trim());
            }
            // Minimal live tables used by Option-A estimator
            jdbc.execute("""
                    create table wm_ads_order_record_valid (
                        workspace_id bigint not null,
                        order_date date not null,
                        parent_sku varchar(80),
                        quantity decimal(18,4) not null,
                        item_cost decimal(18,4) not null
                    )""");
            jdbc.execute("""
                    create table wm_ads_dashboard_ad_daily (
                        workspace_id bigint not null,
                        report_date date not null,
                        ad_spend decimal(24,6) not null
                    )""");
            jdbc.execute("""
                    create table wm_ads_dashboard_ad_sku_daily (
                        workspace_id bigint not null,
                        report_date date not null,
                        parent_sku varchar(80) not null,
                        ad_spend decimal(24,6) not null
                    )""");
            jdbc.execute("""
                    create table wm_ads_profit_assumption (
                        workspace_id bigint not null,
                        parent_sku varchar(80) not null,
                        purchase_cost decimal(18,6),
                        first_leg decimal(18,6),
                        delivery_fee decimal(18,6)
                    )""");

            new FinanceImportService(jdbc, new FinanceWorkbookParser(), new DataSourceTransactionManager(ds))
                    .importDirectory(tempDir, 1L);

            // Seed 2026-11 (10.18..11.14 completed 28-day cycle) + 2026-12 MTD (11.15..11.19 in progress)
            // Known SKU PNK366081 + brand-new SKU PNK369999 (backed by profit_assumption)
            jdbc.update("insert into wm_ads_profit_assumption values (1, 'PNK369999', 25.0, 5.0, 4.5)");
            jdbc.update("insert into wm_ads_order_record_valid values (1, date '2026-10-25', 'PNK366081', 100, 1800.00)");
            jdbc.update("insert into wm_ads_order_record_valid values (1, date '2026-11-10', 'PNK369999', 20, 360.00)");
            jdbc.update("insert into wm_ads_order_record_valid values (1, date '2026-11-19', 'PNK366081', 30, 540.00)");
            jdbc.update("insert into wm_ads_dashboard_ad_daily values (1, date '2026-10-25', 80.00)");
            jdbc.update("insert into wm_ads_dashboard_ad_daily values (1, date '2026-11-18', 20.00)");
            jdbc.update("insert into wm_ads_dashboard_ad_sku_daily values (1, date '2026-10-25', 'PNK366081', 70.00)");
            jdbc.update("insert into wm_ads_dashboard_ad_sku_daily values (1, date '2026-11-18', 'PNK366081', 20.00)");

            FinanceAnalyticsService analytics = new FinanceAnalyticsService(jdbc);

            // Default overview lands on latest finalized month (2026-10) but exposes 2026-11 & 2026-12 estimates on timeline
            var defaultView = analytics.overview(null, null);
            assertThat(defaultView.meta().selectedStartMonth()).isEqualTo("2026-10");
            assertThat(defaultView.meta().estimatedMonths()).containsExactly("2026-11", "2026-12");
            assertThat(defaultView.monthlyTrend()).hasSize(4); // 2026-09, 2026-10, 2026-11(est), 2026-12(in-progress)

            // Query 2026-11 estimated cycle with exchange rate & offsite fee override
            var nov = analytics.overview("2026-11", "2026-11", true, null, null,
                    new BigDecimal("7.0000"), new BigDecimal("500.00"));
            assertThat(nov.meta().selectedAllEstimate()).isTrue();
            assertThat(nov.meta().estimateContext().stage()).isEqualTo(FinanceEstimateService.STAGE_COMPLETED);
            assertThat(nov.meta().estimateContext().periodStart()).isEqualTo("2026-10-18");
            assertThat(nov.meta().estimateContext().periodEnd()).isEqualTo("2026-11-14");
            assertThat(nov.kpi().salesQty()).isEqualTo(120);
            assertThat(nov.kpi().salesRevenueUsd()).isEqualByComparingTo("2160.00");
            assertThat(nov.kpi().adSpendUsd()).isEqualByComparingTo("-80.00");
            assertThat(nov.kpi().offsiteFeeCny()).isEqualByComparingTo("500.00");
            assertThat(nov.styles()).hasSize(2);
            var newSku = nov.styles().stream().filter(s -> s.parentSku().equals("PNK369999")).findFirst().orElseThrow();
            assertThat(newSku.costBasis()).isEqualTo("PROFIT_ASSUMPTION");

            // Query 2026-12 in-progress cycle
            var dec = analytics.overview("2026-12", "2026-12", true, null, null, null, null);
            assertThat(dec.meta().estimateContext().stage()).isEqualTo(FinanceEstimateService.STAGE_IN_PROGRESS);
            assertThat(dec.meta().estimateContext().elapsedDays()).isEqualTo(5);
            assertThat(dec.kpi().salesQty()).isEqualTo(30);

            // Style detail drawer appends estimated months while keeping lifetime strictly finalized
            var detail = analytics.styleDetail("PNK366081");
            assertThat(detail.lifetime().finalizedOnly()).isTrue();
            assertThat(detail.lifetime().monthsActive()).isEqualTo(1);
            assertThat(detail.lifetime().estimatedMonthsCount()).isEqualTo(2);
            assertThat(detail.months()).hasSize(3);
            assertThat(detail.months().get(1).estimated()).isTrue();
            assertThat(detail.months().get(2).estimateStage()).isEqualTo(FinanceEstimateService.STAGE_IN_PROGRESS);

            // Disabling includeEstimate hides estimated cycles completely (both overview and style detail drawer)
            var noEst = analytics.overview("2026-10", "2026-10", false, null, null, null, null);
            assertThat(noEst.meta().estimatedMonths()).isEmpty();
            assertThat(noEst.monthlyTrend()).hasSize(2);
            var detailNoEst = analytics.styleDetail("PNK366081", false);
            assertThat(detailNoEst.months()).hasSize(1);
            assertThat(detailNoEst.lifetime().estimatedMonthsCount()).isZero();
        } finally {
            ds.destroy();
        }
    }

    private void createSampleEra3Workbook(Path target) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Store P&L sheet
            XSSFSheet store = wb.createSheet("利润表（店铺)");
            store.createRow(0).createCell(0).setCellValue("尚依库Walmart2026年利润表");
            XSSFRow hdr = store.createRow(1);
            String[] headers = {"账号", "项目", "费用占比", "汇总", "9月占比", "9月（8.23-9.19）", "10月占比", "10月（9.20-10.17）"};
            for (int i = 0; i < headers.length; i++) hdr.createCell(i).setCellValue(headers[i]);

            Object[][] storeData = {
                    {"PNK-US", "销售收入", 0.0, 1800.0, 0.0, 800.0, 0.0, 1000.0},
                    {"", "销售订单退款", 0.1, -180.0, 0.1, -80.0, 0.1, -100.0},
                    {"", "平台佣金", 0.06, -108.0, 0.06, -48.0, 0.06, -60.0},
                    {"", "WFS配送", 0.30, -540.0, 0.30, -240.0, 0.30, -300.0},
                    {"", "广告费", 0.04, -72.0, 0.04, -32.0, 0.04, -40.0},
                    {"", "仓储费", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "库存赔偿费", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "库存处理费", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "平台其他费用", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "当月结算款", 0.50, 900.0, 0.50, 400.0, 0.50, 500.0},
                    {"", "当月结算比例", 0.0, 0.50, 0.0, 0.50, 0.0, 0.50},
                    {"", "汇率", 0.0, 6.7351, 0.0, 6.7809, 0.0, 6.7351},
                    {"", "结算金额（CNY）", 0.0, 6079.91, 0.0, 2712.36, 0.0, 3367.55},
                    {"", "总销量件数", 0.0, 110, 0.0, 50, 0.0, 60},
                    {"", "退款订单数", 0.0, 11, 0.0, 5, 0.0, 6},
                    {"", "退货件数", 0.0, -4, 0.0, -2, 0.0, -2},
                    {"", "采购成本", 0.0, -3480.0, 0.0, -1500.0, 0.0, -1980.0},
                    {"", "头程运费", 0.0, -895.0, 0.0, -400.0, 0.0, -495.0},
                    {"", "自配送运费", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "站外广告费", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
                    {"", "站外费", 0.0, -100.0, 0.0, 0.0, 0.0, -100.0},
                    {"", "毛利", 0.0, 1604.91, 0.0, 812.36, 0.0, 792.55},
                    {"", "利润率", 0.0, 0.13, 0.0, 0.1497, 0.0, 0.1177},
                    // Other account that must be ignored
                    {"JWD-US", "销售收入", 0.0, 99999.0, 0.0, 50000.0, 0.0, 49999.0},
                    {"", "毛利", 0.0, 12345.0, 0.0, 6000.0, 0.0, 6345.0}
            };
            for (int r = 0; r < storeData.length; r++) {
                XSSFRow row = store.createRow(r + 2);
                for (int c = 0; c < storeData[r].length; c++) {
                    Object val = storeData[r][c];
                    if (val instanceof String s) row.createCell(c).setCellValue(s);
                    else if (val instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                }
            }

            // Style P&L sheet (only 2026-10)
            XSSFSheet style = wb.createSheet("利润表（单款）");
            String[] sh = {
                    "公司", "站点", "SKU", "国家", "Listing状态", "季节", "类目", "负责人", "汇率", "客单价",
                    "销量", "销售额", "退款量", "退款", "退款率", "WFS发货费", "平台费（佣金）", "促销折扣",
                    "广告费", "仓储费", "库存赔偿费", "库存处理费", "其他平台费用", "销售净额", "销售净额(CNY)",
                    "产品成本", "头程成本", "站外费", "利润", "利润率", "退货数量"
            };
            XSSFRow shRow = style.createRow(0);
            for (int i = 0; i < sh.length; i++) shRow.createCell(i).setCellValue(sh[i]);

            List<Object[]> styleRows = List.of(
                    new Object[]{"斐凡", "PNK-US", "PNK366081", "US", "现售", "春夏", "睡衣套装", "邹永华", 6.7351, 16.67,
                            60, 1000.0, 6, -100.0, 0.1, -300.0, -60.0, 0.0,
                            -40.0, 0.0, 0.0, 0.0, 0.0, 500.0, 3367.55,
                            2000.0, 500.0, -100.0, 767.55, 0.1140, -2},
                    new Object[]{"斐凡", "PNK-US", "PNK366073", "US", "清算", "春夏", "女士上衣", "邹永华", 6.7351, 0.0,
                            0, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
                            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                            -20.0, -5.0, 0.0, 25.0, 0.0, -1},
                    new Object[]{"尚依库", "JWD-US", "JWD18501", "US", "现售", "四季", "男士长裤", "Gene", 6.7351, 19.99,
                            500, 9995.0, 10, -200.0, 0.02, -2000.0, -600.0, 0.0,
                            -500.0, -50.0, 0.0, 0.0, 0.0, 6645.0, 44754.74,
                            15000.0, 4000.0, 0.0, 25754.74, 0.38, -5}
            );
            for (int r = 0; r < styleRows.size(); r++) {
                XSSFRow row = style.createRow(r + 1);
                Object[] data = styleRows.get(r);
                for (int c = 0; c < data.length; c++) {
                    Object val = data[c];
                    if (val instanceof String s) row.createCell(c).setCellValue(s);
                    else if (val instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                }
            }

            try (var out = Files.newOutputStream(target)) {
                wb.write(out);
            }
        }
    }
}
