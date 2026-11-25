package com.ai.walmartads.finance;

import com.ai.walmartads.common.WalmartImageUrl;
import com.ai.walmartads.finance.FinanceEstimateService.EstimateOptions;
import com.ai.walmartads.finance.FinanceEstimateService.EstimatedCycle;
import com.ai.walmartads.finance.FinanceEstimateService.EstimatedStore;
import com.ai.walmartads.finance.FinanceEstimateService.EstimatedStyle;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Analytical query service for workspace-scoped monthly store &amp; style financial profit data,
 * seamlessly augmented with Option-A real-time estimates for un-finalized settlement cycles.
 */
@Service
public class FinanceAnalyticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final JdbcTemplate jdbc;
    private final FinanceEstimateService estimateService;

    @Autowired
    public FinanceAnalyticsService(JdbcTemplate jdbc, FinanceEstimateService estimateService) {
        this.jdbc = jdbc;
        this.estimateService = estimateService;
    }

    public FinanceAnalyticsService(JdbcTemplate jdbc) {
        this(jdbc, new FinanceEstimateService(jdbc));
    }

    @Transactional(readOnly = true)
    public OverviewPayload overview(String startMonthParam, String endMonthParam) {
        return overview(startMonthParam, endMonthParam, true, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public OverviewPayload overview(
            String startMonthParam,
            String endMonthParam,
            boolean includeEstimate,
            LocalDate customStartDate,
            LocalDate customEndDate,
            BigDecimal overrideExchangeRate,
            BigDecimal overrideOffsiteFeeCny
    ) {
        List<StoreRow> actualStoreRows = loadStoreRows();
        String workspaceStoreName = WorkspaceContext.current().map(WorkspacePrincipal::storeName).orElse(null);
        if (actualStoreRows.isEmpty()) {
            return OverviewPayload.empty(workspaceStoreName);
        }

        StoreRow latestActual = actualStoreRows.getLast();
        String normStart = normalizeMonth(startMonthParam);
        String normEnd = normalizeMonth(endMonthParam);
        if (normStart != null && normEnd != null && normStart.compareTo(normEnd) > 0) {
            String tmp = normStart;
            normStart = normEnd;
            normEnd = tmp;
        }
        String singleTargetMonth = (normStart != null && normStart.equals(normEnd)) ? normStart : null;

        List<EstimatedCycle> estimatedCycles = includeEstimate
                ? estimateService.estimatePendingCycles(
                        latestActual.reportMonth(),
                        latestActual.settlementPeriod(),
                        latestActual.exchangeRate(),
                        latestActual.accountCode(),
                        latestActual.companyName(),
                        new EstimateOptions(singleTargetMonth, customStartDate, customEndDate,
                                overrideExchangeRate, overrideOffsiteFeeCny))
                : List.of();

        Map<String, EstimatedCycle> estimateByMonth = new LinkedHashMap<>();
        List<StoreRow> allStoreRows = new ArrayList<>(actualStoreRows);
        for (EstimatedCycle cycle : estimatedCycles) {
            estimateByMonth.put(cycle.reportMonth(), cycle);
            allStoreRows.add(toStoreRow(cycle));
        }

        List<MonthOption> availableMonths = allStoreRows.stream()
                .map(r -> new MonthOption(r.reportMonth(), r.settlementPeriod(), r.hasStyleDetail(),
                        r.estimated(), r.estimateStage()))
                .toList();
        List<Integer> availableYears = allStoreRows.stream()
                .map(StoreRow::reportYear)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        List<String> estimatedMonthCodes = estimatedCycles.stream()
                .map(EstimatedCycle::reportMonth)
                .toList();

        // Default selection: latest finalized finance month so initial screen displays official truth
        String startMonth = normStart;
        String endMonth = normEnd;
        if (startMonth == null && endMonth == null) {
            startMonth = latestActual.reportMonth();
            endMonth = latestActual.reportMonth();
        } else if (startMonth == null) {
            startMonth = endMonth;
        } else if (endMonth == null) {
            endMonth = startMonth;
        }

        final String rangeStart = startMonth;
        final String rangeEnd = endMonth;
        List<StoreRow> selectedStoreRows = allStoreRows.stream()
                .filter(r -> r.reportMonth().compareTo(rangeStart) >= 0 && r.reportMonth().compareTo(rangeEnd) <= 0)
                .toList();
        if (selectedStoreRows.isEmpty()) {
            // Fallback when toggling includeEstimate=false while on an estimated month
            selectedStoreRows = List.of(latestActual);
            startMonth = latestActual.reportMonth();
            endMonth = latestActual.reportMonth();
        }
        final String effStart = startMonth;
        final String effEnd = endMonth;

        KpiSummary previousKpi = computePreviousKpi(allStoreRows, effStart, effEnd);
        KpiSummary currentKpi = aggregateStoreKpi(selectedStoreRows);

        List<String> missingStyleMonthsInRange = selectedStoreRows.stream()
                .filter(r -> !r.hasStyleDetail())
                .map(StoreRow::reportMonth)
                .toList();
        int coveredStyleMonthsInRange = (int) selectedStoreRows.stream().filter(StoreRow::hasStyleDetail).count();
        boolean selectedHasEstimate = selectedStoreRows.stream().anyMatch(StoreRow::estimated);
        boolean selectedAllEstimate = !selectedStoreRows.isEmpty() && selectedStoreRows.stream().allMatch(StoreRow::estimated);

        EstimateContext estimateContext = buildEstimateContext(selectedStoreRows, estimateByMonth);

        String selectedSettlementPeriod = selectedStoreRows.size() == 1
                ? selectedStoreRows.getFirst().settlementPeriod()
                : null;
        LocalDateTime lastUpdatedAt = actualStoreRows.stream()
                .map(StoreRow::updatedAt)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        OverviewMeta meta = new OverviewMeta(
                latestActual.accountCode(),
                latestActual.companyName() == null ? workspaceStoreName : latestActual.companyName(),
                effStart,
                effEnd,
                selectedStoreRows.size(),
                selectedSettlementPeriod,
                availableMonths,
                availableYears,
                missingStyleMonthsInRange,
                actualStoreRows.size(),
                (int) actualStoreRows.stream().filter(StoreRow::hasStyleDetail).count(),
                lastUpdatedAt,
                includeEstimate,
                estimatedMonthCodes,
                latestActual.reportMonth(),
                selectedHasEstimate,
                selectedAllEstimate,
                estimateContext
        );

        List<MonthlyTrendPoint> trend = allStoreRows.stream()
                .map(r -> {
                    BigDecimal revCny = scale2(r.salesRevenueUsd().multiply(r.exchangeRate()));
                    BigDecimal unitRefundRate = r.salesQty() > 0
                            ? ratio(BigDecimal.valueOf(r.refundQty()), BigDecimal.valueOf(r.salesQty()))
                            : ZERO;
                    boolean inRange = r.reportMonth().compareTo(effStart) >= 0
                            && r.reportMonth().compareTo(effEnd) <= 0;
                    return new MonthlyTrendPoint(
                            r.reportMonth(),
                            r.settlementPeriod(),
                            scale4(r.exchangeRate()),
                            scale2(r.salesRevenueUsd()),
                            revCny,
                            scale2(r.netSettlementUsd()),
                            scale2(r.netSettlementCny()),
                            scale2(r.grossProfitCny()),
                            scale4(r.profitMargin()),
                            r.salesQty(),
                            r.refundQty(),
                            unitRefundRate,
                            scale2(r.adSpendUsd().abs()),
                            scale2(r.wfsFeeUsd().abs()),
                            scale2(r.purchaseCostCny()),
                            scale2(r.firstLegFreightCny()),
                            scale2(r.offsiteFeeCny()),
                            r.hasStyleDetail(),
                            inRange,
                            r.estimated(),
                            r.estimateStage()
                    );
                })
                .toList();

        List<BreakdownItem> breakdown = buildBreakdown(currentKpi, selectedHasEstimate, selectedAllEstimate, estimateContext);

        // Merge finalized style rows + estimated style rows in [effStart, effEnd]
        List<StyleRow> rangeStyles = new ArrayList<>(loadStyleRows(effStart, effEnd, null));
        for (EstimatedCycle cycle : estimatedCycles) {
            if (cycle.reportMonth().compareTo(effStart) >= 0 && cycle.reportMonth().compareTo(effEnd) <= 0) {
                for (EstimatedStyle es : cycle.styles()) {
                    rangeStyles.add(toStyleRow(es));
                }
            }
        }

        Map<String, StyleMeta> latestMetaBySku = loadLatestStyleMeta();
        Map<String, CatalogInfo> catalogBySku = loadCatalogInfo();
        List<StyleAggregate> aggregatedStyles = aggregateStyles(rangeStyles, latestMetaBySku, catalogBySku);

        int profitable = 0;
        int loss = 0;
        int totalStyleSalesQty = 0;
        BigDecimal totalStyleRevUsd = ZERO;
        BigDecimal totalStyleRevCny = ZERO;
        BigDecimal totalStyleProfitCny = ZERO;
        Set<String> categories = new TreeSet<>();
        Set<String> seasons = new TreeSet<>();
        Set<String> statuses = new TreeSet<>();

        for (StyleAggregate s : aggregatedStyles) {
            if (s.profitCny().compareTo(ZERO) > 0) profitable++;
            else if (s.profitCny().compareTo(ZERO) < 0) loss++;
            totalStyleSalesQty += s.salesQty();
            totalStyleRevUsd = totalStyleRevUsd.add(s.salesRevenueUsd());
            totalStyleRevCny = totalStyleRevCny.add(s.salesRevenueCny());
            totalStyleProfitCny = totalStyleProfitCny.add(s.profitCny());
            if (s.category() != null && !s.category().isBlank()) categories.add(s.category());
            if (s.season() != null && !s.season().isBlank()) seasons.add(s.season());
            if (s.listingStatus() != null && !s.listingStatus().isBlank()) statuses.add(s.listingStatus());
        }

        StyleSummary styleSummary = new StyleSummary(
                aggregatedStyles.size(),
                profitable,
                loss,
                coveredStyleMonthsInRange,
                missingStyleMonthsInRange,
                totalStyleSalesQty,
                scale2(totalStyleRevUsd),
                scale2(totalStyleRevCny),
                scale2(totalStyleProfitCny),
                ratio(totalStyleProfitCny, totalStyleRevCny)
        );

        FilterOptions filterOptions = new FilterOptions(
                List.copyOf(categories),
                List.copyOf(seasons),
                List.copyOf(statuses)
        );

        return new OverviewPayload(
                meta,
                currentKpi,
                previousKpi,
                trend,
                breakdown,
                styleSummary,
                filterOptions,
                aggregatedStyles
        );
    }

    @Transactional(readOnly = true)
    public StyleDetailPayload styleDetail(String parentSkuParam) {
        return styleDetail(parentSkuParam, true);
    }

    @Transactional(readOnly = true)
    public StyleDetailPayload styleDetail(String parentSkuParam, boolean includeEstimate) {
        return styleDetail(parentSkuParam, includeEstimate, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public StyleDetailPayload styleDetail(
            String parentSkuParam,
            boolean includeEstimate,
            String targetEstimateMonth,
            LocalDate customStartDate,
            LocalDate customEndDate,
            BigDecimal exchangeRate,
            BigDecimal offsiteFeeCny
    ) {
        String parentSku = parentSkuParam == null ? "" : parentSkuParam.trim().toUpperCase(Locale.ROOT);
        if (parentSku.isEmpty()) {
            throw new IllegalArgumentException("款号不能为空");
        }

        List<StoreRow> actualStores = loadStoreRows();
        List<StyleRow> actualRows = loadStyleRows(null, null, parentSku);

        List<StyleRow> estimatedRows = new ArrayList<>();
        Map<String, EstimatedStyle> estimateByMonth = new HashMap<>();
        Map<String, String> periodByMonth = new HashMap<>();
        Map<String, String> stageByMonth = new HashMap<>();
        for (StoreRow sr : actualStores) {
            periodByMonth.put(sr.reportMonth(), sr.settlementPeriod());
            stageByMonth.put(sr.reportMonth(), FinanceEstimateService.STAGE_ACTUAL);
        }

        if (includeEstimate && !actualStores.isEmpty()) {
            StoreRow latest = actualStores.getLast();
            List<EstimatedCycle> cycles = estimateService.estimatePendingCycles(
                    latest.reportMonth(), latest.settlementPeriod(), latest.exchangeRate(),
                    latest.accountCode(), latest.companyName(),
                    new EstimateOptions(normalizeMonth(targetEstimateMonth), customStartDate, customEndDate,
                            exchangeRate, offsiteFeeCny));
            for (EstimatedCycle c : cycles) {
                periodByMonth.put(c.reportMonth(), c.settlementPeriod());
                stageByMonth.put(c.reportMonth(), c.estimateStage());
                for (EstimatedStyle es : c.styles()) {
                    if (parentSku.equalsIgnoreCase(es.parentSku())) {
                        estimatedRows.add(toStyleRow(es));
                        estimateByMonth.put(c.reportMonth(), es);
                    }
                }
            }
        }

        if (actualRows.isEmpty() && estimatedRows.isEmpty()) {
            throw new IllegalArgumentException("财务数据中未找到款号：" + parentSku);
        }

        Map<String, CatalogInfo> catalogBySku = loadCatalogInfo();
        CatalogInfo catalog = catalogBySku.get(parentSku);

        String category = null;
        String season = null;
        String listingStatus = null;
        String ownerName = null;
        for (int i = actualRows.size() - 1; i >= 0; i--) {
            StyleRow r = actualRows.get(i);
            if (category == null && r.category() != null) category = r.category();
            if (season == null && r.season() != null) season = r.season();
            if (listingStatus == null && r.listingStatus() != null) listingStatus = r.listingStatus();
            if (ownerName == null && r.ownerName() != null) ownerName = r.ownerName();
        }

        List<StyleRow> allRows = new ArrayList<>(actualRows);
        allRows.addAll(estimatedRows);

        List<StyleMonthDetail> months = new ArrayList<>();
        for (StyleRow r : allRows) {
            BigDecimal mRevCny = r.salesRevenueUsd().multiply(r.exchangeRate());
            EstimatedStyle estimate = estimateByMonth.get(r.reportMonth());
            months.add(new StyleMonthDetail(
                    r.reportMonth(),
                    periodByMonth.get(r.reportMonth()),
                    scale4(r.exchangeRate()),
                    r.salesQty(),
                    scale2(r.avgSellingPriceUsd()),
                    scale2(r.salesRevenueUsd()),
                    scale2(mRevCny),
                    r.refundQty(),
                    scale2(r.refundUsd()),
                    r.salesQty() > 0 ? ratio(BigDecimal.valueOf(r.refundQty()), BigDecimal.valueOf(r.salesQty())) : ZERO,
                    r.returnQty(),
                    scale2(r.wfsFeeUsd()),
                    ratio(r.wfsFeeUsd().abs(), r.salesRevenueUsd()),
                    scale2(r.commissionUsd()),
                    ratio(r.commissionUsd().abs(), r.salesRevenueUsd()),
                    scale2(r.promoDiscountUsd()),
                    scale2(r.adSpendUsd()),
                    ratio(r.adSpendUsd().abs(), r.salesRevenueUsd()),
                    scale2(r.storageFeeUsd()),
                    scale2(r.inventoryReimbursementUsd()),
                    scale2(r.inventoryDisposalUsd()),
                    scale2(r.otherPlatformFeeUsd()),
                    scale2(r.netSettlementUsd()),
                    scale2(r.netSettlementCny()),
                    scale2(r.purchaseCostCny()),
                    scale2(r.firstLegCostCny()),
                    scale2(r.offsiteFeeCny()),
                    scale2(r.profitCny()),
                    ratio(r.profitCny(), mRevCny),
                    r.estimated(),
                    stageByMonth.getOrDefault(r.reportMonth(), FinanceEstimateService.STAGE_ACTUAL),
                    r.costBasis(),
                    estimate == null ? null : estimate.refundBasis(),
                    estimate == null ? null : estimate.commissionBasis(),
                    estimate == null ? null : operatingProfit(r.profitCny(), r.inventoryReimbursementUsd(), r.exchangeRate())
            ));
        }

        // Lifetime KPIs sum strictly finalized finance months when available (so official totals stay stable);
        // for brand-new styles with only estimated cycles so far, summarize their estimated rows.
        List<StyleRow> lifetimeBasis = actualRows.isEmpty() ? estimatedRows : actualRows;
        StyleLifetimeSummary lifetime = summarizeStyleLifetime(lifetimeBasis, !actualRows.isEmpty(), estimatedRows.size());

        return new StyleDetailPayload(
                parentSku,
                catalog == null ? null : catalog.productName(),
                catalog == null ? null : catalog.thumbUrl(),
                category,
                season,
                listingStatus,
                ownerName,
                lifetime,
                months
        );
    }

    private StyleLifetimeSummary summarizeStyleLifetime(List<StyleRow> rows, boolean finalizedOnly, int estimatedMonthsCount) {
        int salesQty = 0, refundQty = 0, returnQty = 0;
        BigDecimal revUsd = ZERO, revCny = ZERO, refundUsd = ZERO, wfsUsd = ZERO, commUsd = ZERO;
        BigDecimal promoUsd = ZERO, adUsd = ZERO, storageUsd = ZERO, reimbUsd = ZERO, dispUsd = ZERO;
        BigDecimal otherUsd = ZERO, netUsd = ZERO, netCny = ZERO, purCny = ZERO, flCny = ZERO, offCny = ZERO, profCny = ZERO;

        for (StyleRow r : rows) {
            BigDecimal mRevCny = r.salesRevenueUsd().multiply(r.exchangeRate());
            salesQty += r.salesQty();
            refundQty += r.refundQty();
            returnQty += r.returnQty();
            revUsd = revUsd.add(r.salesRevenueUsd());
            revCny = revCny.add(mRevCny);
            refundUsd = refundUsd.add(r.refundUsd());
            wfsUsd = wfsUsd.add(r.wfsFeeUsd());
            commUsd = commUsd.add(r.commissionUsd());
            promoUsd = promoUsd.add(r.promoDiscountUsd());
            adUsd = adUsd.add(r.adSpendUsd());
            storageUsd = storageUsd.add(r.storageFeeUsd());
            reimbUsd = reimbUsd.add(r.inventoryReimbursementUsd());
            dispUsd = dispUsd.add(r.inventoryDisposalUsd());
            otherUsd = otherUsd.add(r.otherPlatformFeeUsd());
            netUsd = netUsd.add(r.netSettlementUsd());
            netCny = netCny.add(r.netSettlementCny());
            purCny = purCny.add(r.purchaseCostCny());
            flCny = flCny.add(r.firstLegCostCny());
            offCny = offCny.add(r.offsiteFeeCny());
            profCny = profCny.add(r.profitCny());
        }

        BigDecimal avgFx = !isZero(revUsd) ? revCny.divide(revUsd, 6, RoundingMode.HALF_UP) : ZERO;
        BigDecimal asp = salesQty > 0 ? revUsd.divide(BigDecimal.valueOf(salesQty), 2, RoundingMode.HALF_UP) : ZERO;
        return new StyleLifetimeSummary(
                rows.size(),
                rows.getFirst().reportMonth(),
                rows.getLast().reportMonth(),
                salesQty,
                refundQty,
                returnQty,
                salesQty > 0 ? ratio(BigDecimal.valueOf(refundQty), BigDecimal.valueOf(salesQty)) : ZERO,
                asp,
                scale4(avgFx),
                scale2(revUsd),
                scale2(revCny),
                scale2(refundUsd),
                scale2(wfsUsd),
                ratio(wfsUsd.abs(), revUsd),
                scale2(commUsd),
                ratio(commUsd.abs(), revUsd),
                scale2(promoUsd),
                scale2(adUsd),
                ratio(adUsd.abs(), revUsd),
                scale2(storageUsd),
                scale2(reimbUsd),
                scale2(dispUsd),
                scale2(otherUsd),
                scale2(netUsd),
                scale2(netCny),
                ratio(netUsd, revUsd),
                scale2(purCny),
                ratio(purCny, revCny),
                scale2(flCny),
                ratio(flCny, revCny),
                scale2(offCny),
                scale2(profCny),
                ratio(profCny, revCny),
                finalizedOnly,
                estimatedMonthsCount
        );
    }

    private EstimateContext buildEstimateContext(
            List<StoreRow> selectedRows,
            Map<String, EstimatedCycle> estimateByMonth
    ) {
        List<EstimatedCycle> selectedEstimates = selectedRows.stream()
                .filter(StoreRow::estimated)
                .map(r -> estimateByMonth.get(r.reportMonth()))
                .filter(c -> c != null)
                .toList();
        if (selectedEstimates.isEmpty()) {
            return null;
        }
        EstimatedCycle primary = selectedEstimates.getLast();
        boolean singleEstimate = selectedRows.size() == 1 && selectedEstimates.size() == 1;
        int coverageDays = selectedEstimates.stream().mapToInt(EstimatedCycle::elapsedDays).sum();
        int wfsCoveredDays = selectedEstimates.stream().mapToInt(EstimatedCycle::wfsCoveredDays).sum();
        int storageCoveredDays = selectedEstimates.stream().mapToInt(EstimatedCycle::storageCoveredDays).sum();
        int adCoveredDays = selectedEstimates.stream().mapToInt(EstimatedCycle::adCoveredDays).sum();
        BigDecimal estimateOperatingProfit = selectedEstimates.stream().map(c -> operatingProfit(
                        c.store().grossProfitCny(), c.store().inventoryReimbursementUsd(), c.exchangeRate()))
                .reduce(ZERO, BigDecimal::add);
        return new EstimateContext(
                primary.reportMonth(),
                primary.estimateStage(),
                primary.periodStart().toString(),
                primary.periodEnd().toString(),
                primary.elapsedDays(),
                primary.plannedDays(),
                primary.latestOrderDate() == null ? null : primary.latestOrderDate().toString(),
                primary.latestAdDate() == null ? null : primary.latestAdDate().toString(),
                primary.calibrationMonths(),
                primary.defaultExchangeRate(),
                primary.exchangeRate(),
                primary.offsiteFeeCny(),
                coverageDays == wfsCoveredDays,
                coverageDays == storageCoveredDays,
                singleEstimate,
                selectedEstimates.stream().map(EstimatedCycle::reportMonth).toList(),
                coverageDays,
                wfsCoveredDays,
                storageCoveredDays,
                adCoveredDays,
                primary.wfsCoveredThrough() == null ? null : primary.wfsCoveredThrough().toString(),
                primary.storageCoveredThrough() == null ? null : primary.storageCoveredThrough().toString(),
                estimateOperatingProfit,
                FinanceEstimateService.REFUND_COST_RECOVERY_RATE
        );
    }

    private static BigDecimal operatingProfit(BigDecimal profit, BigDecimal inventoryReimbursement, BigDecimal fx) {
        return scale2(profit.subtract(inventoryReimbursement.multiply(fx)));
    }

    private StoreRow toStoreRow(EstimatedCycle cycle) {
        EstimatedStore s = cycle.store();
        return new StoreRow(
                s.accountCode(), s.companyName(), s.reportYear(), s.reportMonth(), s.settlementPeriod(),
                s.exchangeRate(), s.salesRevenueUsd(), s.refundUsd(), s.commissionUsd(), s.wfsFeeUsd(),
                s.adSpendUsd(), s.storageFeeUsd(), s.inventoryReimbursementUsd(), s.inventoryDisposalUsd(),
                s.promoDiscountUsd(), s.otherPlatformFeeUsd(), s.netSettlementUsd(), s.settlementRatio(),
                s.netSettlementCny(), s.purchaseCostCny(), s.firstLegFreightCny(), ZERO, s.offsiteFeeCny(),
                s.grossProfitCny(), s.profitMargin(), s.salesQty(), s.refundQty(), s.returnQty(),
                true, null, true, cycle.estimateStage()
        );
    }

    private StyleRow toStyleRow(EstimatedStyle es) {
        return new StyleRow(
                es.reportMonth(), es.parentSku(), null, null, null, null,
                es.exchangeRate(), es.avgSellingPriceUsd(), es.salesQty(), es.salesRevenueUsd(),
                es.refundQty(), es.refundUsd(), es.returnQty(), es.wfsFeeUsd(), es.commissionUsd(),
                es.promoDiscountUsd(), es.adSpendUsd(), es.storageFeeUsd(),
                es.inventoryReimbursementUsd(), es.inventoryDisposalUsd(), es.otherPlatformFeeUsd(),
                es.netSettlementUsd(), es.netSettlementCny(), es.purchaseCostCny(),
                es.firstLegCostCny(), es.offsiteFeeCny(), es.profitCny(),
                true, es.costBasis()
        );
    }

    private KpiSummary computePreviousKpi(List<StoreRow> allRows, String startMonth, String endMonth) {
        YearMonth start = YearMonth.parse(startMonth);
        YearMonth end = YearMonth.parse(endMonth);
        long spanMonths = (end.getYear() - start.getYear()) * 12L + (end.getMonthValue() - start.getMonthValue()) + 1L;
        YearMonth prevEnd = start.minusMonths(1);
        YearMonth prevStart = prevEnd.minusMonths(spanMonths - 1);
        String pStart = prevStart.toString();
        String pEnd = prevEnd.toString();
        List<StoreRow> prevRows = allRows.stream()
                .filter(r -> r.reportMonth().compareTo(pStart) >= 0 && r.reportMonth().compareTo(pEnd) <= 0)
                .toList();
        if (prevRows.isEmpty()) {
            return null;
        }
        return aggregateStoreKpi(prevRows);
    }

    private KpiSummary aggregateStoreKpi(List<StoreRow> rows) {
        BigDecimal revUsd = ZERO, revCny = ZERO, refundUsd = ZERO, commUsd = ZERO, wfsUsd = ZERO;
        BigDecimal adUsd = ZERO, storageUsd = ZERO, reimbUsd = ZERO, dispUsd = ZERO, promoUsd = ZERO;
        BigDecimal otherUsd = ZERO, netUsd = ZERO, netCny = ZERO, purCny = ZERO, flCny = ZERO;
        BigDecimal offAdCny = ZERO, offFeeCny = ZERO, gpCny = ZERO;
        int salesQty = 0, refundQty = 0, returnQty = 0;

        for (StoreRow r : rows) {
            revUsd = revUsd.add(r.salesRevenueUsd());
            revCny = revCny.add(r.salesRevenueUsd().multiply(r.exchangeRate()));
            refundUsd = refundUsd.add(r.refundUsd());
            commUsd = commUsd.add(r.commissionUsd());
            wfsUsd = wfsUsd.add(r.wfsFeeUsd());
            adUsd = adUsd.add(r.adSpendUsd());
            storageUsd = storageUsd.add(r.storageFeeUsd());
            reimbUsd = reimbUsd.add(r.inventoryReimbursementUsd());
            dispUsd = dispUsd.add(r.inventoryDisposalUsd());
            promoUsd = promoUsd.add(r.promoDiscountUsd());
            otherUsd = otherUsd.add(r.otherPlatformFeeUsd());
            netUsd = netUsd.add(r.netSettlementUsd());
            netCny = netCny.add(r.netSettlementCny());
            purCny = purCny.add(r.purchaseCostCny());
            flCny = flCny.add(r.firstLegFreightCny());
            offAdCny = offAdCny.add(r.offsiteAdCny());
            offFeeCny = offFeeCny.add(r.offsiteFeeCny());
            gpCny = gpCny.add(r.grossProfitCny());
            salesQty += r.salesQty();
            refundQty += r.refundQty();
            returnQty += r.returnQty();
        }

        BigDecimal avgFx = !isZero(revUsd)
                ? revCny.divide(revUsd, 6, RoundingMode.HALF_UP)
                : (rows.isEmpty() ? ZERO : rows.getLast().exchangeRate());
        BigDecimal aspUsd = salesQty > 0
                ? revUsd.divide(BigDecimal.valueOf(salesQty), 2, RoundingMode.HALF_UP)
                : ZERO;
        BigDecimal totalOffsiteCny = offAdCny.add(offFeeCny);

        return new KpiSummary(
                rows.size(),
                scale4(avgFx),
                scale2(aspUsd),
                scale2(revUsd),
                scale2(revCny),
                scale2(refundUsd),
                scale2(commUsd),
                scale2(wfsUsd),
                scale2(adUsd),
                scale2(storageUsd),
                scale2(reimbUsd),
                scale2(dispUsd),
                scale2(promoUsd),
                scale2(otherUsd),
                scale2(netUsd),
                scale2(netCny),
                ratio(netUsd, revUsd),
                scale2(purCny),
                scale2(flCny),
                scale2(totalOffsiteCny),
                scale2(gpCny),
                ratio(gpCny, revCny),
                salesQty,
                refundQty,
                returnQty,
                salesQty > 0 ? ratio(BigDecimal.valueOf(refundQty), BigDecimal.valueOf(salesQty)) : ZERO,
                ratio(refundUsd.abs(), revUsd),
                ratio(commUsd.abs(), revUsd),
                ratio(wfsUsd.abs(), revUsd),
                ratio(adUsd.abs(), revUsd),
                ratio(storageUsd.abs(), revUsd),
                ratio(purCny, revCny),
                ratio(flCny, revCny),
                ratio(totalOffsiteCny, revCny)
        );
    }

    private List<BreakdownItem> buildBreakdown(
            KpiSummary kpi,
            boolean hasEstimate,
            boolean allEstimate,
            EstimateContext ctx
    ) {
        BigDecimal fx = kpi.avgExchangeRate();
        BigDecimal revUsd = kpi.salesRevenueUsd();
        BigDecimal revCny = kpi.salesRevenueCny();
        BigDecimal otherNetUsd = kpi.inventoryDisposalUsd()
                .add(kpi.promoDiscountUsd())
                .add(kpi.otherPlatformFeeUsd());

        String salesTag = !hasEstimate ? "财务正式" : (allEstimate ? "领星实测" : "正式+实测");
        String adsTag = !hasEstimate ? "财务正式" : ctx != null && ctx.adCoveredDays() < ctx.coverageDays()
                ? "广告未齐" : salesTag;
        String wfsTag = ctx == null ? "财务正式" : coverageTag(ctx.wfsCoveredDays(), ctx.coverageDays());
        String stoTag = ctx == null ? "财务正式" : coverageTag(ctx.storageCoveredDays(), ctx.coverageDays());
        String estTag = !hasEstimate ? "财务正式" : (allEstimate ? "费率预估" : "含预估");
        String sumTag = !hasEstimate ? "财务正式" : (allEstimate ? "推算汇总" : "含预估");
        String offTag = !hasEstimate ? "财务正式" : (allEstimate ? "手工参数" : "含预估");

        List<BreakdownItem> items = new ArrayList<>();
        items.add(new BreakdownItem("sales", "销售收入", "REVENUE", kpi.salesRevenueUsd(), kpi.salesRevenueCny(), BigDecimal.ONE, salesTag));
        items.add(new BreakdownItem("refund", "销售订单退款", "PLATFORM_DEDUCTION", kpi.refundUsd(), scale2(kpi.refundUsd().multiply(fx)), ratio(kpi.refundUsd(), revUsd), estTag));
        items.add(new BreakdownItem("wfs", "WFS 配送费", "PLATFORM_DEDUCTION", kpi.wfsFeeUsd(), scale2(kpi.wfsFeeUsd().multiply(fx)), ratio(kpi.wfsFeeUsd(), revUsd), wfsTag));
        items.add(new BreakdownItem("commission", "平台佣金", "PLATFORM_DEDUCTION", kpi.commissionUsd(), scale2(kpi.commissionUsd().multiply(fx)), ratio(kpi.commissionUsd(), revUsd), hasEstimate ? "按订单规则预估" : "财务正式"));
        items.add(new BreakdownItem("ads", "站内广告费", "PLATFORM_DEDUCTION", kpi.adSpendUsd(), scale2(kpi.adSpendUsd().multiply(fx)), ratio(kpi.adSpendUsd(), revUsd), adsTag));
        items.add(new BreakdownItem("storage", "WFS 仓储费", "PLATFORM_DEDUCTION", kpi.storageFeeUsd(), scale2(kpi.storageFeeUsd().multiply(fx)), ratio(kpi.storageFeeUsd(), revUsd), stoTag));
        if (!isZero(kpi.inventoryReimbursementUsd())) {
            BigDecimal reimbursement = kpi.inventoryReimbursementUsd();
            items.add(new BreakdownItem("inventory_reimbursement", "库存赔偿及找回冲回", "PLATFORM_ADJUSTMENT",
                    reimbursement, scale2(reimbursement.multiply(fx)), ratio(reimbursement, revUsd),
                    hasEstimate ? "账单已记录" : "财务正式"));
        }
        items.add(new BreakdownItem("other_platform", "处理/折扣及其他", "PLATFORM_ADJUSTMENT", scale2(otherNetUsd), scale2(otherNetUsd.multiply(fx)), ratio(otherNetUsd, revUsd), wfsTag));
        items.add(new BreakdownItem("settlement", "平台结算净额", "SUBTOTAL", kpi.netSettlementUsd(), kpi.netSettlementCny(), kpi.settlementRatio(), sumTag));
        items.add(new BreakdownItem("purchase", "产品采购成本", "CNY_COST", null, kpi.purchaseCostCny().negate(), ratio(kpi.purchaseCostCny().negate(), revCny), estTag));
        items.add(new BreakdownItem("first_leg", "头程运费", "CNY_COST", null, kpi.firstLegFreightCny().negate(), ratio(kpi.firstLegFreightCny().negate(), revCny), estTag));
        if (!isZero(kpi.offsiteFeeCny())) {
            items.add(new BreakdownItem("offsite", "站外费用/冲回", "CNY_COST", null, kpi.offsiteFeeCny().negate(), ratio(kpi.offsiteFeeCny().negate(), revCny), offTag));
        }
        items.add(new BreakdownItem("profit", "最终毛利", "PROFIT", null, kpi.grossProfitCny(), kpi.profitMargin(), sumTag));
        return items;
    }

    private static String coverageTag(int coveredDays, int periodDays) {
        if (coveredDays == 0) return "费率预估";
        return coveredDays == periodDays ? "WFS实扣" : "实扣+预估";
    }

    private List<StyleAggregate> aggregateStyles(
            List<StyleRow> rows,
            Map<String, StyleMeta> latestMetaBySku,
            Map<String, CatalogInfo> catalogBySku
    ) {
        Map<String, List<StyleRow>> grouped = new LinkedHashMap<>();
        for (StyleRow r : rows) {
            grouped.computeIfAbsent(r.parentSku(), ignored -> new ArrayList<>()).add(r);
        }

        List<StyleAggregate> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<StyleRow>> entry : grouped.entrySet()) {
            String sku = entry.getKey();
            List<StyleRow> group = entry.getValue();
            int salesQty = 0, refundQty = 0, returnQty = 0;
            BigDecimal revUsd = ZERO, revCny = ZERO, refundUsd = ZERO, wfsUsd = ZERO, commUsd = ZERO;
            BigDecimal promoUsd = ZERO, adUsd = ZERO, storageUsd = ZERO, reimbUsd = ZERO, dispUsd = ZERO;
            BigDecimal otherUsd = ZERO, netUsd = ZERO, netCny = ZERO, purCny = ZERO, flCny = ZERO, offCny = ZERO, profCny = ZERO;
            boolean hasEstimated = false;
            String costBasis = "FINANCE_ACTUAL";

            Set<String> months = new LinkedHashSet<>();
            for (StyleRow r : group) {
                months.add(r.reportMonth());
                if (r.estimated()) {
                    hasEstimated = true;
                    costBasis = r.costBasis();
                }
                salesQty += r.salesQty();
                refundQty += r.refundQty();
                returnQty += r.returnQty();
                revUsd = revUsd.add(r.salesRevenueUsd());
                revCny = revCny.add(r.salesRevenueUsd().multiply(r.exchangeRate()));
                refundUsd = refundUsd.add(r.refundUsd());
                wfsUsd = wfsUsd.add(r.wfsFeeUsd());
                commUsd = commUsd.add(r.commissionUsd());
                promoUsd = promoUsd.add(r.promoDiscountUsd());
                adUsd = adUsd.add(r.adSpendUsd());
                storageUsd = storageUsd.add(r.storageFeeUsd());
                reimbUsd = reimbUsd.add(r.inventoryReimbursementUsd());
                dispUsd = dispUsd.add(r.inventoryDisposalUsd());
                otherUsd = otherUsd.add(r.otherPlatformFeeUsd());
                netUsd = netUsd.add(r.netSettlementUsd());
                netCny = netCny.add(r.netSettlementCny());
                purCny = purCny.add(r.purchaseCostCny());
                flCny = flCny.add(r.firstLegCostCny());
                offCny = offCny.add(r.offsiteFeeCny());
                profCny = profCny.add(r.profitCny());
            }

            StyleMeta meta = latestMetaBySku.get(sku);
            CatalogInfo cat = catalogBySku.get(sku);
            BigDecimal asp = salesQty > 0 ? revUsd.divide(BigDecimal.valueOf(salesQty), 2, RoundingMode.HALF_UP) : ZERO;

            result.add(new StyleAggregate(
                    sku,
                    cat == null ? null : cat.productName(),
                    cat == null ? null : cat.thumbUrl(),
                    meta == null ? null : meta.category(),
                    meta == null ? null : meta.season(),
                    meta == null ? null : meta.listingStatus(),
                    meta == null ? null : meta.ownerName(),
                    months.size(),
                    salesQty,
                    refundQty,
                    returnQty,
                    salesQty > 0 ? ratio(BigDecimal.valueOf(refundQty), BigDecimal.valueOf(salesQty)) : ZERO,
                    asp,
                    scale2(revUsd),
                    scale2(revCny),
                    scale2(refundUsd),
                    scale2(wfsUsd),
                    ratio(wfsUsd.abs(), revUsd),
                    scale2(commUsd),
                    ratio(commUsd.abs(), revUsd),
                    scale2(promoUsd),
                    scale2(adUsd),
                    ratio(adUsd.abs(), revUsd),
                    scale2(storageUsd),
                    scale2(reimbUsd),
                    scale2(dispUsd),
                    scale2(otherUsd),
                    scale2(netUsd),
                    scale2(netCny),
                    scale2(purCny),
                    ratio(purCny, revCny),
                    scale2(flCny),
                    ratio(flCny, revCny),
                    scale2(offCny),
                    scale2(profCny),
                    ratio(profCny, revCny),
                    hasEstimated,
                    costBasis
            ));
        }

        result.sort(Comparator.comparing(StyleAggregate::profitCny).reversed()
                .thenComparing(StyleAggregate::salesRevenueUsd, Comparator.reverseOrder())
                .thenComparing(StyleAggregate::parentSku));
        return result;
    }

    private List<StoreRow> loadStoreRows() {
        return jdbc.query("""
                select account_code, company_name, report_year, report_month, settlement_period,
                       exchange_rate, sales_revenue_usd, refund_usd, commission_usd, wfs_fee_usd,
                       ad_spend_usd, storage_fee_usd, inventory_reimbursement_usd, inventory_disposal_usd,
                       promo_discount_usd, other_platform_fee_usd, net_settlement_usd, settlement_ratio,
                       net_settlement_cny, purchase_cost_cny, first_leg_freight_cny, offsite_ad_cny,
                       offsite_fee_cny, gross_profit_cny, profit_margin, sales_qty, refund_qty,
                       return_qty, has_style_detail, updated_at
                  from wm_ads_finance_store_monthly
                 order by report_month asc
                """, (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("updated_at");
            return new StoreRow(
                    rs.getString("account_code"),
                    rs.getString("company_name"),
                    rs.getInt("report_year"),
                    rs.getString("report_month"),
                    rs.getString("settlement_period"),
                    bd(rs.getBigDecimal("exchange_rate")),
                    bd(rs.getBigDecimal("sales_revenue_usd")),
                    bd(rs.getBigDecimal("refund_usd")),
                    bd(rs.getBigDecimal("commission_usd")),
                    bd(rs.getBigDecimal("wfs_fee_usd")),
                    bd(rs.getBigDecimal("ad_spend_usd")),
                    bd(rs.getBigDecimal("storage_fee_usd")),
                    bd(rs.getBigDecimal("inventory_reimbursement_usd")),
                    bd(rs.getBigDecimal("inventory_disposal_usd")),
                    bd(rs.getBigDecimal("promo_discount_usd")),
                    bd(rs.getBigDecimal("other_platform_fee_usd")),
                    bd(rs.getBigDecimal("net_settlement_usd")),
                    bd(rs.getBigDecimal("settlement_ratio")),
                    bd(rs.getBigDecimal("net_settlement_cny")),
                    bd(rs.getBigDecimal("purchase_cost_cny")),
                    bd(rs.getBigDecimal("first_leg_freight_cny")),
                    bd(rs.getBigDecimal("offsite_ad_cny")),
                    bd(rs.getBigDecimal("offsite_fee_cny")),
                    bd(rs.getBigDecimal("gross_profit_cny")),
                    bd(rs.getBigDecimal("profit_margin")),
                    rs.getInt("sales_qty"),
                    rs.getInt("refund_qty"),
                    rs.getInt("return_qty"),
                    rs.getBoolean("has_style_detail"),
                    ts == null ? null : ts.toLocalDateTime(),
                    false,
                    FinanceEstimateService.STAGE_ACTUAL
            );
        });
    }

    private List<StyleRow> loadStyleRows(String startMonth, String endMonth, String parentSku) {
        StringBuilder sql = new StringBuilder("""
                select report_month, parent_sku, listing_status, season, category, owner_name,
                       exchange_rate, avg_selling_price_usd, sales_qty, sales_revenue_usd,
                       refund_qty, refund_usd, return_qty, wfs_fee_usd, commission_usd,
                       promo_discount_usd, ad_spend_usd, storage_fee_usd,
                       inventory_reimbursement_usd, inventory_disposal_usd, other_platform_fee_usd,
                       net_settlement_usd, net_settlement_cny, purchase_cost_cny, first_leg_cost_cny,
                       offsite_fee_cny, profit_cny
                  from wm_ads_finance_style_monthly
                 where 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (startMonth != null) {
            sql.append(" and report_month >= ?");
            params.add(startMonth);
        }
        if (endMonth != null) {
            sql.append(" and report_month <= ?");
            params.add(endMonth);
        }
        if (parentSku != null) {
            sql.append(" and parent_sku = ?");
            params.add(parentSku);
        }
        sql.append(" order by report_month asc, parent_sku asc");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new StyleRow(
                rs.getString("report_month"),
                rs.getString("parent_sku"),
                rs.getString("listing_status"),
                rs.getString("season"),
                rs.getString("category"),
                rs.getString("owner_name"),
                bd(rs.getBigDecimal("exchange_rate")),
                bd(rs.getBigDecimal("avg_selling_price_usd")),
                rs.getInt("sales_qty"),
                bd(rs.getBigDecimal("sales_revenue_usd")),
                rs.getInt("refund_qty"),
                bd(rs.getBigDecimal("refund_usd")),
                rs.getInt("return_qty"),
                bd(rs.getBigDecimal("wfs_fee_usd")),
                bd(rs.getBigDecimal("commission_usd")),
                bd(rs.getBigDecimal("promo_discount_usd")),
                bd(rs.getBigDecimal("ad_spend_usd")),
                bd(rs.getBigDecimal("storage_fee_usd")),
                bd(rs.getBigDecimal("inventory_reimbursement_usd")),
                bd(rs.getBigDecimal("inventory_disposal_usd")),
                bd(rs.getBigDecimal("other_platform_fee_usd")),
                bd(rs.getBigDecimal("net_settlement_usd")),
                bd(rs.getBigDecimal("net_settlement_cny")),
                bd(rs.getBigDecimal("purchase_cost_cny")),
                bd(rs.getBigDecimal("first_leg_cost_cny")),
                bd(rs.getBigDecimal("offsite_fee_cny")),
                bd(rs.getBigDecimal("profit_cny")),
                false,
                "FINANCE_ACTUAL"
        ), params.toArray());
    }

    private Map<String, StyleMeta> loadLatestStyleMeta() {
        Map<String, StyleMeta> map = new HashMap<>();
        jdbc.query("""
                select parent_sku, category, season, listing_status, owner_name
                  from wm_ads_finance_style_monthly
                 order by report_month asc
                """, rs -> {
            String sku = rs.getString("parent_sku");
            StyleMeta prev = map.get(sku);
            String cat = nonBlankOr(rs.getString("category"), prev == null ? null : prev.category());
            String sea = nonBlankOr(rs.getString("season"), prev == null ? null : prev.season());
            String st = nonBlankOr(rs.getString("listing_status"), prev == null ? null : prev.listingStatus());
            String own = nonBlankOr(rs.getString("owner_name"), prev == null ? null : prev.ownerName());
            map.put(sku, new StyleMeta(cat, sea, st, own));
        });
        return map;
    }

    private Map<String, CatalogInfo> loadCatalogInfo() {
        Map<String, CatalogInfo> map = new HashMap<>();
        try {
            jdbc.query("""
                    select parent_sku,
                           max(product_name) as product_name,
                           max(main_image_url) as main_image_url
                      from wm_ads_product_record
                     where current_version = true and parent_sku is not null
                     group by parent_sku
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null) {
                    String normalized = sku.trim().toUpperCase(Locale.ROOT);
                    map.put(normalized, new CatalogInfo(
                            rs.getString("product_name"),
                            WalmartImageUrl.thumb(rs.getString("main_image_url"))
                    ));
                }
            });
        } catch (DataAccessException ignored) {
            // Allow finance queries to succeed in minimal test schemas
        }
        return map;
    }

    private static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (!trimmed.matches("^20\\d{2}-(0[1-9]|1[0-2])$")) {
            throw new IllegalArgumentException("月份格式需为 YYYY-MM：" + trimmed);
        }
        return trimmed;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }

    private static BigDecimal bd(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static boolean isZero(BigDecimal v) {
        return v == null || v.abs().compareTo(new BigDecimal("0.000001")) < 0;
    }

    private static BigDecimal ratio(BigDecimal num, BigDecimal den) {
        if (isZero(den)) return ZERO;
        return num.divide(den, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }

    private record StoreRow(
            String accountCode, String companyName, int reportYear, String reportMonth, String settlementPeriod,
            BigDecimal exchangeRate, BigDecimal salesRevenueUsd, BigDecimal refundUsd, BigDecimal commissionUsd,
            BigDecimal wfsFeeUsd, BigDecimal adSpendUsd, BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd, BigDecimal inventoryDisposalUsd, BigDecimal promoDiscountUsd,
            BigDecimal otherPlatformFeeUsd, BigDecimal netSettlementUsd, BigDecimal settlementRatio,
            BigDecimal netSettlementCny, BigDecimal purchaseCostCny, BigDecimal firstLegFreightCny,
            BigDecimal offsiteAdCny, BigDecimal offsiteFeeCny, BigDecimal grossProfitCny, BigDecimal profitMargin,
            int salesQty, int refundQty, int returnQty, boolean hasStyleDetail, LocalDateTime updatedAt,
            boolean estimated, String estimateStage
    ) {
    }

    private record StyleRow(
            String reportMonth, String parentSku, String listingStatus, String season, String category, String ownerName,
            BigDecimal exchangeRate, BigDecimal avgSellingPriceUsd, int salesQty, BigDecimal salesRevenueUsd,
            int refundQty, BigDecimal refundUsd, int returnQty, BigDecimal wfsFeeUsd, BigDecimal commissionUsd,
            BigDecimal promoDiscountUsd, BigDecimal adSpendUsd, BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd, BigDecimal inventoryDisposalUsd, BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd, BigDecimal netSettlementCny, BigDecimal purchaseCostCny,
            BigDecimal firstLegCostCny, BigDecimal offsiteFeeCny, BigDecimal profitCny,
            boolean estimated, String costBasis
    ) {
    }

    private record StyleMeta(String category, String season, String listingStatus, String ownerName) {
    }

    private record CatalogInfo(String productName, String thumbUrl) {
    }

    public record OverviewPayload(
            OverviewMeta meta,
            KpiSummary kpi,
            KpiSummary previousKpi,
            List<MonthlyTrendPoint> monthlyTrend,
            List<BreakdownItem> costBreakdown,
            StyleSummary styleSummary,
            FilterOptions filterOptions,
            List<StyleAggregate> styles
    ) {
        private static OverviewPayload empty(String workspaceStoreName) {
            return new OverviewPayload(
                    new OverviewMeta(null, workspaceStoreName, null, null, 0, null, List.of(), List.of(), List.of(),
                            0, 0, null, true, List.of(), null, false, false, null),
                    null, null, List.of(), List.of(),
                    new StyleSummary(0, 0, 0, 0, List.of(), 0, ZERO, ZERO, ZERO, ZERO),
                    new FilterOptions(List.of(), List.of(), List.of()),
                    List.of()
            );
        }
    }

    public record MonthOption(
            String month,
            String settlementPeriod,
            boolean hasStyleDetail,
            boolean estimated,
            String estimateStage
    ) {
    }

    public record EstimateContext(
            String month,
            String stage,
            String periodStart,
            String periodEnd,
            int elapsedDays,
            int plannedDays,
            String latestOrderDate,
            String latestAdDate,
            List<String> calibrationMonths,
            BigDecimal defaultExchangeRate,
            BigDecimal exchangeRateUsed,
            BigDecimal offsiteFeeCnyUsed,
            boolean wfsActualUsed,
            boolean storageActualUsed,
            boolean customDatesEditable,
            List<String> includedEstimateMonths,
            int coverageDays,
            int wfsCoveredDays,
            int storageCoveredDays,
            int adCoveredDays,
            String wfsCoveredThrough,
            String storageCoveredThrough,
            BigDecimal operatingProfitCny,
            BigDecimal refundCostRecoveryRate
    ) {
    }

    public record OverviewMeta(
            String accountCode,
            String companyName,
            String selectedStartMonth,
            String selectedEndMonth,
            int selectedMonthCount,
            String selectedSettlementPeriod,
            List<MonthOption> availableMonths,
            List<Integer> availableYears,
            List<String> missingStyleMonthsInRange,
            int totalStoreMonths,
            int totalStyleMonths,
            LocalDateTime lastUpdatedAt,
            boolean includeEstimate,
            List<String> estimatedMonths,
            String latestFinalMonth,
            boolean selectedHasEstimate,
            boolean selectedAllEstimate,
            EstimateContext estimateContext
    ) {
    }

    public record KpiSummary(
            int monthCount,
            BigDecimal avgExchangeRate,
            BigDecimal avgSellingPriceUsd,
            BigDecimal salesRevenueUsd,
            BigDecimal salesRevenueCny,
            BigDecimal refundUsd,
            BigDecimal commissionUsd,
            BigDecimal wfsFeeUsd,
            BigDecimal adSpendUsd,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal promoDiscountUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal settlementRatio,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegFreightCny,
            BigDecimal offsiteFeeCny,
            BigDecimal grossProfitCny,
            BigDecimal profitMargin,
            int salesQty,
            int refundQty,
            int returnQty,
            BigDecimal unitRefundRate,
            BigDecimal refundAmountRate,
            BigDecimal commissionRate,
            BigDecimal wfsFeeRate,
            BigDecimal adSpendRate,
            BigDecimal storageFeeRate,
            BigDecimal purchaseCostRate,
            BigDecimal firstLegRate,
            BigDecimal offsiteRate
    ) {
    }

    public record MonthlyTrendPoint(
            String month,
            String settlementPeriod,
            BigDecimal exchangeRate,
            BigDecimal salesRevenueUsd,
            BigDecimal salesRevenueCny,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal grossProfitCny,
            BigDecimal profitMargin,
            int salesQty,
            int refundQty,
            BigDecimal unitRefundRate,
            BigDecimal adSpendUsd,
            BigDecimal wfsFeeUsd,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegFreightCny,
            BigDecimal offsiteFeeCny,
            boolean hasStyleDetail,
            boolean inSelectedRange,
            boolean estimated,
            String estimateStage
    ) {
    }

    public record BreakdownItem(
            String key,
            String label,
            String group,
            BigDecimal amountUsd,
            BigDecimal amountCny,
            BigDecimal shareOfRevenue,
            String sourceTag
    ) {
    }

    public record StyleSummary(
            int totalStyles,
            int profitableStyles,
            int lossStyles,
            int coveredStyleMonths,
            List<String> missingStyleMonths,
            int totalSalesQty,
            BigDecimal totalSalesRevenueUsd,
            BigDecimal totalSalesRevenueCny,
            BigDecimal totalProfitCny,
            BigDecimal overallProfitMargin
    ) {
    }

    public record FilterOptions(List<String> categories, List<String> seasons, List<String> statuses) {
    }

    public record StyleAggregate(
            String parentSku,
            String productName,
            String thumbUrl,
            String category,
            String season,
            String listingStatus,
            String ownerName,
            int monthsActive,
            int salesQty,
            int refundQty,
            int returnQty,
            BigDecimal unitRefundRate,
            BigDecimal avgSellingPriceUsd,
            BigDecimal salesRevenueUsd,
            BigDecimal salesRevenueCny,
            BigDecimal refundUsd,
            BigDecimal wfsFeeUsd,
            BigDecimal wfsFeeRate,
            BigDecimal commissionUsd,
            BigDecimal commissionRate,
            BigDecimal promoDiscountUsd,
            BigDecimal adSpendUsd,
            BigDecimal adSpendRate,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal purchaseCostCny,
            BigDecimal purchaseCostRate,
            BigDecimal firstLegCostCny,
            BigDecimal firstLegRate,
            BigDecimal offsiteFeeCny,
            BigDecimal profitCny,
            BigDecimal profitMargin,
            boolean hasEstimatedData,
            String costBasis
    ) {
    }

    public record StyleDetailPayload(
            String parentSku,
            String productName,
            String thumbUrl,
            String category,
            String season,
            String listingStatus,
            String ownerName,
            StyleLifetimeSummary lifetime,
            List<StyleMonthDetail> months
    ) {
    }

    public record StyleLifetimeSummary(
            int monthsActive,
            String firstMonth,
            String lastMonth,
            int salesQty,
            int refundQty,
            int returnQty,
            BigDecimal unitRefundRate,
            BigDecimal avgSellingPriceUsd,
            BigDecimal avgExchangeRate,
            BigDecimal salesRevenueUsd,
            BigDecimal salesRevenueCny,
            BigDecimal refundUsd,
            BigDecimal wfsFeeUsd,
            BigDecimal wfsFeeRate,
            BigDecimal commissionUsd,
            BigDecimal commissionRate,
            BigDecimal promoDiscountUsd,
            BigDecimal adSpendUsd,
            BigDecimal adSpendRate,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal settlementRatio,
            BigDecimal purchaseCostCny,
            BigDecimal purchaseCostRate,
            BigDecimal firstLegCostCny,
            BigDecimal firstLegRate,
            BigDecimal offsiteFeeCny,
            BigDecimal profitCny,
            BigDecimal profitMargin,
            boolean finalizedOnly,
            int estimatedMonthsCount
    ) {
    }

    public record StyleMonthDetail(
            String month,
            String settlementPeriod,
            BigDecimal exchangeRate,
            int salesQty,
            BigDecimal avgSellingPriceUsd,
            BigDecimal salesRevenueUsd,
            BigDecimal salesRevenueCny,
            int refundQty,
            BigDecimal refundUsd,
            BigDecimal unitRefundRate,
            int returnQty,
            BigDecimal wfsFeeUsd,
            BigDecimal wfsFeeRate,
            BigDecimal commissionUsd,
            BigDecimal commissionRate,
            BigDecimal promoDiscountUsd,
            BigDecimal adSpendUsd,
            BigDecimal adSpendRate,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegCostCny,
            BigDecimal offsiteFeeCny,
            BigDecimal profitCny,
            BigDecimal profitMargin,
            boolean estimated,
            String estimateStage,
            String costBasis,
            String refundBasis,
            String commissionBasis,
            BigDecimal operatingProfitCny
    ) {
    }
}
