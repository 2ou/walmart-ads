package com.ai.walmartads.finance;

import com.ai.walmartads.product.application.RefundRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Option-A real-time estimation engine for un-finalized Walmart finance cycles.
 *
 * <p>Combines live workspace data (LingXing orders &amp; ad spend, plus WFS settlement/storage if already
 * uploaded for the period) with recency-weighted per-style historical finance calibration rates
 * and current catalog or manually maintained assumptions.</p>
 */
@Service
public class FinanceEstimateService {

    public static final String STAGE_ACTUAL = "ACTUAL";
    public static final String STAGE_COMPLETED = "COMPLETED_ESTIMATE";
    public static final String STAGE_IN_PROGRESS = "IN_PROGRESS_ESTIMATE";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_FX = new BigDecimal("6.8000");
    static final BigDecimal REFUND_COST_RECOVERY_RATE = new BigDecimal("0.70");
    private static final BigDecimal NEW_SKU_RETURN_WFS_FACTOR = new BigDecimal("0.45");
    private static final BigDecimal MIN_HISTORICAL_REVENUE = new BigDecimal("250");
    private static final BigDecimal LOW_PRICE_COMMISSION = new BigDecimal("0.05");
    private static final BigDecimal MID_PRICE_COMMISSION = new BigDecimal("0.10");
    private static final BigDecimal HIGH_PRICE_COMMISSION = new BigDecimal("0.15");
    private static final BigDecimal LOW_PRICE_THRESHOLD = new BigDecimal("15");
    private static final BigDecimal MID_PRICE_THRESHOLD = new BigDecimal("20");
    private static final String UNASSIGNED_STYLE = "未归属";
    private static final int CYCLE_DAYS = 28;
    private static final Pattern PERIOD_END_MD = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\s*$");

    private final JdbcTemplate jdbc;
    private final RefundRateService refundRateService;
    private final FinanceCommissionRuleService commissionRules;

    public FinanceEstimateService(JdbcTemplate jdbc) {
        this(jdbc, new RefundRateService(new NamedParameterJdbcTemplate(jdbc)),
                new FinanceCommissionRuleService(jdbc));
    }

    @Autowired
    public FinanceEstimateService(JdbcTemplate jdbc, RefundRateService refundRateService,
                                  FinanceCommissionRuleService commissionRules) {
        this.jdbc = jdbc;
        this.refundRateService = refundRateService;
        this.commissionRules = commissionRules;
    }

    public record EstimateOptions(
            String targetEstimateMonth,
            LocalDate customStartDate,
            LocalDate customEndDate,
            BigDecimal overrideExchangeRate,
            BigDecimal overrideOffsiteFeeCny
    ) {
        public static EstimateOptions defaults() {
            return new EstimateOptions(null, null, null, null, null);
        }
    }

    public record EstimatedCycle(
            String reportMonth,
            String settlementPeriod,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate plannedEnd,
            int elapsedDays,
            int plannedDays,
            String estimateStage,
            LocalDate latestOrderDate,
            LocalDate latestAdDate,
            List<String> calibrationMonths,
            BigDecimal defaultExchangeRate,
            BigDecimal exchangeRate,
            BigDecimal offsiteFeeCny,
            boolean wfsActualUsed,
            boolean storageActualUsed,
            int adCoveredDays,
            int wfsCoveredDays,
            int storageCoveredDays,
            LocalDate wfsCoveredThrough,
            LocalDate storageCoveredThrough,
            EstimatedStore store,
            List<EstimatedStyle> styles
    ) {
    }

    public record EstimatedStore(
            String accountCode,
            String companyName,
            int reportYear,
            String reportMonth,
            String settlementPeriod,
            BigDecimal exchangeRate,
            BigDecimal salesRevenueUsd,
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
            BigDecimal settlementRatio,
            BigDecimal netSettlementCny,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegFreightCny,
            BigDecimal offsiteFeeCny,
            BigDecimal grossProfitCny,
            BigDecimal profitMargin,
            int salesQty,
            int refundQty,
            int returnQty
    ) {
    }

    public record EstimatedStyle(
            String reportMonth,
            String parentSku,
            BigDecimal exchangeRate,
            BigDecimal avgSellingPriceUsd,
            int salesQty,
            BigDecimal salesRevenueUsd,
            int refundQty,
            BigDecimal refundUsd,
            int returnQty,
            BigDecimal wfsFeeUsd,
            BigDecimal commissionUsd,
            BigDecimal promoDiscountUsd,
            BigDecimal adSpendUsd,
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
            String costBasis,
            String refundBasis,
            String commissionBasis
    ) {
    }

    public List<EstimatedCycle> estimatePendingCycles(
            String latestFinalMonth,
            String latestFinalSettlementPeriod,
            BigDecimal latestFinalExchangeRate,
            String accountCode,
            String companyName,
            EstimateOptions options
    ) {
        if (latestFinalMonth == null || latestFinalMonth.isBlank()) {
            return List.of();
        }
        LocalDate latestOrderDate = queryMaxDate("""
                select max(o.order_date)
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                """);
        if (latestOrderDate == null) {
            return List.of();
        }
        LocalDate lastFinalEnd = resolveFinalPeriodEnd(latestFinalMonth, latestFinalSettlementPeriod);
        if (!latestOrderDate.isAfter(lastFinalEnd)) {
            return List.of();
        }
        SourceCutoffs sourceCutoffs = new SourceCutoffs(latestOrderDate,
                queryMaxDate("select max(report_date) from wm_ads_dashboard_ad_daily"),
                queryMaxDate("select max(period_end) from wm_ads_wfs_fulfillment_fee where txn_date is not null"),
                queryMaxDate("select max(period_end) from wm_ads_wfs_storage_fee"));

        LocalDate firstPendingDay = lastFinalEnd.plusDays(1);
        DateRange custom = customRange(options, firstPendingDay, latestOrderDate);
        Calibration calibration = buildCalibration(latestFinalExchangeRate);
        YearMonth baseYm = YearMonth.parse(latestFinalMonth);
        LocalDate cursorStart = firstPendingDay;
        List<EstimatedCycle> cycles = new ArrayList<>();
        int step = 1;

        while (!cursorStart.isAfter(latestOrderDate) && step <= 6) {
            String monthCode = baseYm.plusMonths(step).toString();
            LocalDate plannedEnd = cursorStart.plusDays(CYCLE_DAYS - 1L);
            boolean targetMonth = options != null && monthCode.equals(options.targetEstimateMonth());
            boolean isTargetOverride = custom != null && targetMonth;
            LocalDate effectiveStart = cursorStart;
            LocalDate effectiveEnd = earlier(latestOrderDate, plannedEnd);
            cursorStart = plannedEnd.plusDays(1);
            step++;

            if (isTargetOverride) {
                effectiveStart = custom.start();
                effectiveEnd = custom.end();
            } else if (custom != null && monthCode.compareTo(options.targetEstimateMonth()) < 0) {
                effectiveEnd = earlier(effectiveEnd, custom.start().minusDays(1));
            } else if (custom != null) {
                effectiveStart = later(effectiveStart, custom.end().plusDays(1));
            }
            if (effectiveStart.isAfter(effectiveEnd)) continue;

            boolean inProgress = !isTargetOverride && latestOrderDate.isBefore(plannedEnd);
            String stage = inProgress ? STAGE_IN_PROGRESS : STAGE_COMPLETED;
            int elapsedDays = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
            LocalDate effectivePlannedEnd = custom == null || inProgress ? plannedEnd : effectiveEnd;
            int plannedDays = Math.toIntExact(ChronoUnit.DAYS.between(effectiveStart, effectivePlannedEnd) + 1);

            boolean appliesOverride = options != null && (options.targetEstimateMonth() == null || targetMonth);
            BigDecimal fx = (appliesOverride && options.overrideExchangeRate() != null
                    && options.overrideExchangeRate().compareTo(ZERO) > 0)
                    ? options.overrideExchangeRate()
                    : calibration.defaultExchangeRate();
            BigDecimal offsiteCny = (appliesOverride && options.overrideOffsiteFeeCny() != null)
                    ? options.overrideOffsiteFeeCny()
                    : ZERO;

            EstimatedCycle cycle = computeCycle(
                    monthCode,
                    accountCode,
                    companyName,
                    effectiveStart,
                    effectiveEnd,
                    effectivePlannedEnd,
                    elapsedDays,
                    plannedDays,
                    stage,
                    sourceCutoffs,
                    calibration,
                    fx,
                    offsiteCny
            );
            if (cycle != null) {
                cycles.add(cycle);
            }
        }
        return cycles;
    }

    private static DateRange customRange(EstimateOptions options, LocalDate firstPendingDay, LocalDate lastOrderDay) {
        if (options == null || options.targetEstimateMonth() == null
                || options.customStartDate() == null || options.customEndDate() == null) {
            return null;
        }
        LocalDate start = later(firstPendingDay, earlier(options.customStartDate(), options.customEndDate()));
        LocalDate end = earlier(lastOrderDay, later(options.customStartDate(), options.customEndDate()));
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("自定义账期与可用的未结账订单日期没有交集");
        }
        return new DateRange(start, end);
    }

    private EstimatedCycle computeCycle(
            String reportMonth,
            String accountCode,
            String companyName,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate plannedEnd,
            int elapsedDays,
            int plannedDays,
            String stage,
            SourceCutoffs sourceCutoffs,
            Calibration cal,
            BigDecimal fx,
            BigDecimal offsiteTotalCny
    ) {
        WfsActuals wfsActual = loadWfsActuals(startDate, endDate);
        StorageActuals storageActual = loadStorageActuals(startDate, endDate);
        Map<String, OrderAgg> ordersBySku = loadLiveOrders(startDate, endDate,
                wfsActual.coverage(), storageActual.coverage(), cal.commissionRules(), cal.itemsBySku());
        Map<String, BigDecimal> adsBySku = loadLiveAdsBySku(startDate, endDate);
        BigDecimal totalLiveAds = loadLiveAdsTotal(startDate, endDate);
        int adCoveredDays = countAdCoveredDays(startDate, endDate);

        if (ordersBySku.isEmpty() && adsBySku.isEmpty() && isZero(totalLiveAds) && isZero(offsiteTotalCny)
                && wfsActual.bySku().isEmpty() && storageActual.bySku().isEmpty()) {
            return null;
        }

        Set<String> allSkus = new LinkedHashSet<>(ordersBySku.keySet());
        allSkus.addAll(adsBySku.keySet());
        allSkus.addAll(wfsActual.bySku().keySet());
        allSkus.addAll(storageActual.bySku().keySet());

        BigDecimal totalRevUsd = ordersBySku.values().stream()
                .map(OrderAgg::revUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal sumAttributedAds = adsBySku.values().stream().reduce(ZERO, BigDecimal::add);
        BigDecimal unattributedAds = adCoveredDays > 0 ? totalLiveAds.subtract(sumAttributedAds) : ZERO;
        if (isZero(totalRevUsd) && (!isZero(unattributedAds) || !isZero(offsiteTotalCny))) {
            allSkus.add(UNASSIGNED_STYLE);
        }

        List<EstimatedStyle> styleRows = new ArrayList<>();
        BigDecimal sRevUsd = ZERO;
        BigDecimal sRefundUsd = ZERO;
        BigDecimal sCommUsd = ZERO;
        BigDecimal sWfsUsd = ZERO;
        BigDecimal sAdUsd = ZERO;
        BigDecimal sStorageUsd = ZERO;
        BigDecimal sReimbUsd = ZERO;
        BigDecimal sDispUsd = ZERO;
        BigDecimal sOtherUsd = ZERO;
        BigDecimal sNetUsd = ZERO;
        BigDecimal sNetCny = ZERO;
        BigDecimal sPurCny = ZERO;
        BigDecimal sFlCny = ZERO;
        BigDecimal sOffCny = ZERO;
        BigDecimal sProfCny = ZERO;
        int sQty = 0;
        int sRefQty = 0;
        int sRetQty = 0;

        for (String sku : allSkus) {
            OrderAgg o = ordersBySku.getOrDefault(sku, OrderAgg.EMPTY);
            int qty = o.qty().setScale(0, RoundingMode.HALF_UP).intValue();
            BigDecimal revUsd = o.revUsd();
            BigDecimal revShare = !isZero(totalRevUsd) ? revUsd.divide(totalRevUsd, 8, RoundingMode.HALF_UP) : ZERO;
            BigDecimal aspUsd = safeDiv(revUsd, o.qty());

            SkuCalibration sc = cal.bySku().get(sku);
            AssumptionCost pa = cal.assumptions().get(sku);
            BigDecimal prodDeliveryFee = cal.productDeliveryFees().get(sku);

            RefundRates refundRates = refundRates(sku, sc, pa, cal);
            BigDecimal refAmtRate = refundRates.amount();
            BigDecimal unitRefRate = refundRates.units();
            BigDecimal refundUsd = revUsd.multiply(refAmtRate).negate();
            int refundQty = BigDecimal.valueOf(qty).multiply(unitRefRate).setScale(0, RoundingMode.HALF_UP).intValue();
            int returnQty = BigDecimal.valueOf(qty).multiply(cal.storeReturnRate()).setScale(0, RoundingMode.HALF_UP).intValue();

            BigDecimal commUsd = o.grossCommission().multiply(ONE.subtract(refAmtRate)).negate();

            WfsSkuFee wf = wfsActual.bySku().getOrDefault(sku, WfsSkuFee.ZERO_FEE);
            BigDecimal wfsUnit = estimatedWfsUnit(prodDeliveryFee, pa, sc, unitRefRate, cal.storeWfsUnitFeeUsd());
            BigDecimal wfsUsd = wf.wfsFeeUsd().subtract(o.qtyWithoutWfs().multiply(wfsUnit));
            BigDecimal reimbUsd = wf.reimbUsd();
            BigDecimal dispUsd = wf.disposalUsd();
            BigDecimal otherUsd = wf.otherUsd();

            // 4. Live Ad Spend (exact SKU ad spend + finance-style revenue-share apportionment of any unattributed diff)
            BigDecimal skuAd = adsBySku.getOrDefault(sku, ZERO).add(
                    isZero(totalRevUsd) && UNASSIGNED_STYLE.equals(sku) ? unattributedAds : unattributedAds.multiply(revShare));
            BigDecimal adUsd = skuAd.negate();

            BigDecimal storageUsd = storageActual.bySku().getOrDefault(sku, ZERO)
                    .add(o.revWithoutStorage().multiply(cal.storeStorageRate())).negate();

            // 6. Net Settlement USD & CNY
            BigDecimal netUsd = revUsd.add(refundUsd).add(commUsd).add(wfsUsd).add(adUsd)
                    .add(storageUsd).add(reimbUsd).add(dispUsd).add(otherUsd);
            BigDecimal netCny = netUsd.multiply(fx);

            // 7. Purchase Cost & First-Leg Cost CNY
            BigDecimal purCny;
            BigDecimal flCny;
            String costBasis;
            boolean historyCost = sc != null && sc.qty() >= 15 && positive(sc.purchaseUnitCny());
            if (pa != null && positive(pa.purchaseCost())) {
                BigDecimal paidUnits = o.qty().multiply(ONE.subtract(unitRefRate.multiply(REFUND_COST_RECOVERY_RATE)));
                purCny = paidUnits.multiply(pa.purchaseCost());
                if (pa.firstLeg() != null && pa.firstLeg().signum() >= 0) {
                    flCny = paidUnits.multiply(pa.firstLeg());
                } else {
                    flCny = historyCost ? o.qty().multiply(sc.firstLegUnitCny())
                            : revUsd.multiply(fx).multiply(cal.storeFirstLegRate());
                }
                costBasis = "PROFIT_ASSUMPTION";
            } else if (historyCost) {
                purCny = o.qty().multiply(sc.purchaseUnitCny());
                flCny = o.qty().multiply(sc.firstLegUnitCny());
                costBasis = "FINANCE_HIST";
            } else {
                BigDecimal revCny = revUsd.multiply(fx);
                purCny = revCny.multiply(cal.storePurchaseRate());
                flCny = revCny.multiply(cal.storeFirstLegRate());
                costBasis = "STORE_FALLBACK";
            }

            // 8. Optional user-entered offsite fee apportioned by revenue share
            BigDecimal skuOffsiteCny = isZero(totalRevUsd) && UNASSIGNED_STYLE.equals(sku)
                    ? offsiteTotalCny : offsiteTotalCny.multiply(revShare);
            BigDecimal profitCny = netCny.subtract(purCny).subtract(flCny).subtract(skuOffsiteCny);

            styleRows.add(new EstimatedStyle(
                    reportMonth,
                    sku,
                    scale4(fx),
                    scale2(aspUsd),
                    qty,
                    scale2(revUsd),
                    refundQty,
                    scale2(refundUsd),
                    returnQty,
                    scale2(wfsUsd),
                    scale2(commUsd),
                    ZERO,
                    scale2(adUsd),
                    scale2(storageUsd),
                    scale2(reimbUsd),
                    scale2(dispUsd),
                    scale2(otherUsd),
                    scale2(netUsd),
                    scale2(netCny),
                    scale2(purCny),
                    scale2(flCny),
                    scale2(skuOffsiteCny),
                    scale2(profitCny),
                    costBasis,
                    refundRates.basis(),
                    o.commissionBasis()
            ));

            sQty += qty;
            sRefQty += refundQty;
            sRetQty += returnQty;
            sRevUsd = sRevUsd.add(revUsd);
            sRefundUsd = sRefundUsd.add(refundUsd);
            sCommUsd = sCommUsd.add(commUsd);
            sWfsUsd = sWfsUsd.add(wfsUsd);
            sAdUsd = sAdUsd.add(adUsd);
            sStorageUsd = sStorageUsd.add(storageUsd);
            sReimbUsd = sReimbUsd.add(reimbUsd);
            sDispUsd = sDispUsd.add(dispUsd);
            sOtherUsd = sOtherUsd.add(otherUsd);
            sNetUsd = sNetUsd.add(netUsd);
            sNetCny = sNetCny.add(netCny);
            sPurCny = sPurCny.add(purCny);
            sFlCny = sFlCny.add(flCny);
            sOffCny = sOffCny.add(skuOffsiteCny);
            sProfCny = sProfCny.add(profitCny);
        }

        BigDecimal revCny = sRevUsd.multiply(fx);
        BigDecimal settlementRatio = !isZero(sRevUsd) ? sNetUsd.divide(sRevUsd, 6, RoundingMode.HALF_UP) : ZERO;
        BigDecimal profitMargin = !isZero(revCny) ? sProfCny.divide(revCny, 6, RoundingMode.HALF_UP) : ZERO;

        String periodLabel = formatPeriod(startDate, endDate)
                + (STAGE_IN_PROGRESS.equals(stage) ? " · " + elapsedDays + "/" + plannedDays + "天" : "");

        EstimatedStore store = new EstimatedStore(
                accountCode,
                companyName,
                YearMonth.parse(reportMonth).getYear(),
                reportMonth,
                periodLabel,
                scale4(fx),
                scale2(sRevUsd),
                scale2(sRefundUsd),
                scale2(sCommUsd),
                scale2(sWfsUsd),
                scale2(sAdUsd),
                scale2(sStorageUsd),
                scale2(sReimbUsd),
                scale2(sDispUsd),
                ZERO,
                scale2(sOtherUsd),
                scale2(sNetUsd),
                settlementRatio,
                scale2(sNetCny),
                scale2(sPurCny),
                scale2(sFlCny),
                scale2(sOffCny),
                scale2(sProfCny),
                profitMargin,
                sQty,
                sRefQty,
                sRetQty
        );

        return new EstimatedCycle(
                reportMonth,
                periodLabel,
                startDate,
                endDate,
                plannedEnd,
                elapsedDays,
                plannedDays,
                stage,
                sourceCutoffs.orders(),
                sourceCutoffs.ads(),
                cal.calibrationMonths(),
                scale4(cal.defaultExchangeRate()),
                scale4(fx),
                scale2(offsiteTotalCny),
                wfsActual.coverage().days() > 0,
                storageActual.coverage().days() > 0,
                adCoveredDays,
                wfsActual.coverage().days(),
                storageActual.coverage().days(),
                sourceCutoffs.wfs(),
                sourceCutoffs.storage(),
                store,
                styleRows
        );
    }

    private static RefundRates refundRates(String sku, SkuCalibration sc, AssumptionCost pa, Calibration cal) {
        BigDecimal manual = pa == null ? null : pa.refundRate();
        if (pa != null && pa.preferManualRefundRate() && validRate(manual)) {
            return new RefundRates(manual, manual, "MANUAL");
        }
        BigDecimal cumulative = cal.refundRatesBySku().get(sku);
        if (validRate(cumulative)) {
            return new RefundRates(cumulative, cumulative, "CUMULATIVE");
        }
        boolean styleAmount = sc != null && sc.revUsd().compareTo(MIN_HISTORICAL_REVENUE) >= 0
                && validRate(sc.refundAmountRate());
        boolean styleUnits = sc != null && sc.qty() >= 15 && validRate(sc.unitRefundRate());
        if (!styleAmount && !styleUnits && validRate(manual)) {
            return new RefundRates(manual, manual, "MANUAL_FALLBACK");
        }
        BigDecimal amount = styleAmount ? sc.refundAmountRate() : cal.storeRefundAmountRate();
        BigDecimal units = styleUnits ? sc.unitRefundRate() : cal.storeUnitRefundRate();
        String basis = styleAmount && styleUnits ? "FINANCE_HIST"
                : (styleAmount || styleUnits ? "FINANCE_STORE_MIX" : "STORE_FALLBACK");
        return new RefundRates(amount, units, basis);
    }

    private static BigDecimal estimatedWfsUnit(BigDecimal catalogFee, AssumptionCost pa, SkuCalibration sc,
                                               BigDecimal refundRate, BigDecimal accountFee) {
        BigDecimal baseFee = pa != null && positive(pa.deliveryFee()) ? pa.deliveryFee() : catalogFee;
        if (positive(baseFee)) {
            return baseFee.multiply(ONE.add(refundRate.multiply(NEW_SKU_RETURN_WFS_FACTOR)));
        }
        return sc != null && sc.qty() >= 15 && positive(sc.wfsUnitFeeUsd()) ? sc.wfsUnitFeeUsd() : accountFee;
    }

    private Calibration buildCalibration(BigDecimal latestFinalExchangeRate) {
        // Latest 3 finalized finance months that have style details
        List<String> calMonths = jdbc.queryForList("""
                select report_month
                  from wm_ads_finance_store_monthly
                 where has_style_detail = true
                 order by report_month desc
                 limit 3
                """, String.class);
        List<String> chronological = new ArrayList<>(calMonths);
        chronological.sort(Comparator.naturalOrder());

        // Assign recency weights (newest = 3, middle = 2, oldest = 1)
        Map<String, BigDecimal> monthWeight = new HashMap<>();
        int n = chronological.size();
        for (int i = 0; i < n; i++) {
            monthWeight.put(chronological.get(i), BigDecimal.valueOf(i + 1L));
        }

        // Store-level weighted calibration
        BigDecimal wRev = ZERO, wRevCny = ZERO, wRefUsd = ZERO, wWfs = ZERO, wSto = ZERO;
        BigDecimal wPur = ZERO, wFl = ZERO;
        BigDecimal wQty = ZERO, wRefQty = ZERO, wRetQty = ZERO;

        if (!chronological.isEmpty()) {
            String inClause = String.join(",", chronological.stream().map(m -> "'" + m + "'").toList());
            List<Map<String, Object>> storeRows = jdbc.queryForList(
                    "select report_month, exchange_rate, sales_revenue_usd, refund_usd, wfs_fee_usd, "
                            + "storage_fee_usd, "
                            + "purchase_cost_cny, first_leg_freight_cny, sales_qty, refund_qty, return_qty "
                            + "from wm_ads_finance_store_monthly where report_month in (" + inClause + ")");
            for (Map<String, Object> r : storeRows) {
                BigDecimal w = monthWeight.getOrDefault((String) r.get("report_month"), ONE);
                BigDecimal rev = bd(r.get("sales_revenue_usd"));
                BigDecimal fx = bd(r.get("exchange_rate"));
                wRev = wRev.add(rev.multiply(w));
                wRevCny = wRevCny.add(rev.multiply(fx).multiply(w));
                wRefUsd = wRefUsd.add(bd(r.get("refund_usd")).abs().multiply(w));
                wWfs = wWfs.add(bd(r.get("wfs_fee_usd")).abs().multiply(w));
                wSto = wSto.add(bd(r.get("storage_fee_usd")).abs().multiply(w));
                wPur = wPur.add(bd(r.get("purchase_cost_cny")).multiply(w));
                wFl = wFl.add(bd(r.get("first_leg_freight_cny")).multiply(w));
                wQty = wQty.add(bd(r.get("sales_qty")).multiply(w));
                wRefQty = wRefQty.add(bd(r.get("refund_qty")).multiply(w));
                wRetQty = wRetQty.add(bd(r.get("return_qty")).multiply(w));
            }
        }

        // Per-SKU weighted calibration across those latest 3 style months
        Map<String, SkuAcc> skuAccs = new HashMap<>();
        if (!chronological.isEmpty()) {
            String inClause = String.join(",", chronological.stream().map(m -> "'" + m + "'").toList());
            List<Map<String, Object>> styleRows = jdbc.queryForList(
                    "select report_month, parent_sku, sales_qty, sales_revenue_usd, refund_qty, refund_usd, "
                            + "wfs_fee_usd, purchase_cost_cny, first_leg_cost_cny "
                            + "from wm_ads_finance_style_monthly where report_month in (" + inClause + ")");
            for (Map<String, Object> r : styleRows) {
                String sku = String.valueOf(r.get("parent_sku")).trim().toUpperCase(Locale.ROOT);
                BigDecimal w = monthWeight.getOrDefault((String) r.get("report_month"), ONE);
                SkuAcc acc = skuAccs.computeIfAbsent(sku, ignored -> new SkuAcc());
                int rawQty = bd(r.get("sales_qty")).intValue();
                acc.rawQty += rawQty;
                acc.rawRev = acc.rawRev.add(bd(r.get("sales_revenue_usd")));
                acc.wQty = acc.wQty.add(BigDecimal.valueOf(rawQty).multiply(w));
                acc.wRev = acc.wRev.add(bd(r.get("sales_revenue_usd")).multiply(w));
                acc.wRefQty = acc.wRefQty.add(bd(r.get("refund_qty")).multiply(w));
                acc.wRefUsd = acc.wRefUsd.add(bd(r.get("refund_usd")).abs().multiply(w));
                acc.wWfsUsd = acc.wWfsUsd.add(bd(r.get("wfs_fee_usd")).abs().multiply(w));
                acc.wPurCny = acc.wPurCny.add(bd(r.get("purchase_cost_cny")).multiply(w));
                acc.wFlCny = acc.wFlCny.add(bd(r.get("first_leg_cost_cny")).multiply(w));
            }
        }

        Map<String, SkuCalibration> bySku = new HashMap<>();
        for (Map.Entry<String, SkuAcc> entry : skuAccs.entrySet()) {
            SkuAcc a = entry.getValue();
            BigDecimal refAmtRate = safeDiv(a.wRefUsd, a.wRev);
            BigDecimal unitRefRate = safeDiv(a.wRefQty, a.wQty);
            BigDecimal wfsUnit = safeDiv(a.wWfsUsd, a.wQty);
            BigDecimal purUnit = safeDiv(a.wPurCny, a.wQty);
            BigDecimal flUnit = safeDiv(a.wFlCny, a.wQty).max(ZERO);
            bySku.put(entry.getKey(), new SkuCalibration(
                    a.rawQty, a.rawRev, refAmtRate, unitRefRate, wfsUnit, purUnit, flUnit));
        }

        Map<String, AssumptionCost> assumptions = loadProfitAssumptions();
        Map<String, BigDecimal> productDeliveryFees = loadProductDeliveryFees();
        Map<String, List<FinanceCommissionRuleService.Rule>> approvedRules = commissionRules.loadApprovedRules();
        Map<String, String> itemsBySku = approvedRules.isEmpty() ? Map.of() : loadProductItemIds();

        BigDecimal fx = (latestFinalExchangeRate != null && latestFinalExchangeRate.compareTo(ZERO) > 0)
                ? latestFinalExchangeRate : DEFAULT_FX;

        return new Calibration(
                chronological,
                fx,
                safeDiv(wRefUsd, wRev).max(ZERO).min(ONE),
                safeDiv(wRefQty, wQty).max(ZERO).min(ONE),
                safeDiv(wRetQty, wQty).max(ZERO).min(ONE),
                safeDiv(wWfs, wQty),
                safeDiv(wSto, wRev),
                safeDiv(wPur, wRevCny).max(ZERO),
                safeDiv(wFl, wRevCny).max(ZERO),
                bySku,
                assumptions,
                productDeliveryFees,
                loadRefundRates(),
                approvedRules,
                itemsBySku
        );
    }

    private Map<String, OrderAgg> loadLiveOrders(LocalDate startDate, LocalDate endDate,
                                                Coverage wfsCoverage, Coverage storageCoverage,
                                                Map<String, List<FinanceCommissionRuleService.Rule>> approved,
                                                Map<String, String> itemsBySku) {
        Map<String, OrderAgg> map = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    select o.order_date, upper(trim(o.parent_sku)) as parent_sku, upper(trim(o.sku)) as seller_sku,
                           coalesce(sum(o.quantity), 0) as qty, o.item_cost as unit_price
                      from wm_ads_order_record_valid o
                      join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                     where o.order_date between ? and ?
                       and o.parent_sku is not null and trim(o.parent_sku) <> ''
                     group by o.order_date, upper(trim(o.parent_sku)), upper(trim(o.sku)), o.item_cost
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                LocalDate day = rs.getDate("order_date").toLocalDate();
                BigDecimal qty = bd(rs.getBigDecimal("qty"));
                BigDecimal unitPrice = bd(rs.getBigDecimal("unit_price"));
                BigDecimal revenue = qty.multiply(unitPrice);
                String sellerSku = rs.getString("seller_sku");
                String itemId = sellerSku == null ? "" : itemsBySku.getOrDefault(sellerSku, "");
                var approvedRate = FinanceCommissionRuleService.matchRate(approved, itemId, day, unitPrice);
                BigDecimal commissionRate = approvedRate.orElseGet(() -> standardCommissionRate(unitPrice));
                BigDecimal commission = unitPrice.multiply(commissionRate)
                        .setScale(2, RoundingMode.HALF_UP).multiply(qty);
                OrderAgg row = new OrderAgg(qty, revenue, commission, approvedRate.isPresent() ? qty : ZERO,
                        wfsCoverage.covers(day) ? ZERO : qty, storageCoverage.covers(day) ? ZERO : revenue);
                map.merge(sku, row, OrderAgg::plus);
            }, Date.valueOf(startDate), Date.valueOf(endDate));
        } catch (DataAccessException ignored) {
            // Minimal test schemas may not include order tables
        }
        return map;
    }

    private static BigDecimal standardCommissionRate(BigDecimal unitPrice) {
        if (unitPrice.compareTo(LOW_PRICE_THRESHOLD) <= 0) return LOW_PRICE_COMMISSION;
        if (unitPrice.compareTo(MID_PRICE_THRESHOLD) <= 0) return MID_PRICE_COMMISSION;
        return HIGH_PRICE_COMMISSION;
    }

    private Map<String, BigDecimal> loadLiveAdsBySku(LocalDate startDate, LocalDate endDate) {
        Map<String, BigDecimal> map = new HashMap<>();
        try {
            jdbc.query("""
                    select upper(trim(parent_sku)) as parent_sku,
                           coalesce(sum(ad_spend), 0) as spend
                      from wm_ads_dashboard_ad_sku_daily
                     where report_date between ? and ?
                       and parent_sku is not null and trim(parent_sku) <> ''
                     group by upper(trim(parent_sku))
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, bd(rs.getBigDecimal("spend")));
                }
            }, Date.valueOf(startDate), Date.valueOf(endDate));
        } catch (DataAccessException ignored) {
            // Minimal test schemas may not include ad summary tables
        }
        return map;
    }

    private BigDecimal loadLiveAdsTotal(LocalDate startDate, LocalDate endDate) {
        try {
            BigDecimal total = jdbc.queryForObject("""
                    select coalesce(sum(ad_spend), 0)
                      from wm_ads_dashboard_ad_daily
                     where report_date between ? and ?
                    """, BigDecimal.class, Date.valueOf(startDate), Date.valueOf(endDate));
            return total == null ? ZERO : total;
        } catch (DataAccessException ignored) {
            return ZERO;
        }
    }

    private int countAdCoveredDays(LocalDate startDate, LocalDate endDate) {
        try {
            Integer days = jdbc.queryForObject("""
                    select count(distinct report_date)
                      from wm_ads_dashboard_ad_daily
                     where report_date between ? and ?
                    """, Integer.class, Date.valueOf(startDate), Date.valueOf(endDate));
            return days == null ? 0 : days;
        } catch (DataAccessException ignored) {
            return 0;
        }
    }

    private WfsActuals loadWfsActuals(LocalDate startDate, LocalDate endDate) {
        try {
            Set<LocalDate> days = new LinkedHashSet<>();
            jdbc.query("""
                    select distinct period_start, period_end
                      from wm_ads_wfs_fulfillment_fee
                     where period_start <= ? and period_end >= ? and txn_date is not null
                    """, rs -> {
                DateRange period = new DateRange(rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate());
                period.addDays(days, startDate, endDate);
            }, Date.valueOf(endDate), Date.valueOf(startDate));

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select upper(trim(coalesce(parent_sku, ''))) as parent_sku,
                           txn_date, transaction_type,
                           coalesce(sum(net_payable), 0) as net_payable
                      from wm_ads_wfs_fulfillment_fee
                     where txn_date between ? and ?
                     group by upper(trim(coalesce(parent_sku, ''))), txn_date, transaction_type
                    """, Date.valueOf(startDate), Date.valueOf(endDate));

            Map<String, BigDecimal[]> bySkuRaw = new HashMap<>();
            for (Map<String, Object> r : rows) {
                String sku = feeParent((String) r.get("parent_sku"));
                String type = String.valueOf(r.get("transaction_type"));
                BigDecimal payable = bd(r.get("net_payable")); // positive = fee charged to seller, negative = credit to seller
                BigDecimal signedPnl = payable.negate();

                int slot;
                if ("FulfillmentFee".equalsIgnoreCase(type) || "ReturnProcessingFee".equalsIgnoreCase(type)) {
                    slot = 0; // wfsFeeUsd
                    days.add(((Date) r.get("txn_date")).toLocalDate());
                } else if ("LostInventory".equalsIgnoreCase(type) || "FoundInventory".equalsIgnoreCase(type)) {
                    slot = 1; // reimbUsd
                } else if ("RC_InventoryDisposalFee".equalsIgnoreCase(type)
                        || "InventoryDisposalOrder".equalsIgnoreCase(type)) {
                    slot = 2; // dispUsd
                } else if ("StorageFee".equalsIgnoreCase(type) || "LongTermStorageFee".equalsIgnoreCase(type)) {
                    // Storage comes from the item-level storage report to avoid charging it twice.
                    continue;
                } else {
                    slot = 3; // return adjustments and other WFS fees remain in operating profit
                }

                BigDecimal[] arr = bySkuRaw.computeIfAbsent(sku, ignored -> new BigDecimal[]{ZERO, ZERO, ZERO, ZERO});
                arr[slot] = arr[slot].add(signedPnl);
            }
            Map<String, WfsSkuFee> bySku = new HashMap<>();
            bySkuRaw.forEach((k, v) -> bySku.put(k, new WfsSkuFee(v[0], v[1], v[2], v[3])));
            return new WfsActuals(bySku, new Coverage(days));
        } catch (DataAccessException ignored) {
            return new WfsActuals(Map.of(), Coverage.EMPTY);
        }
    }

    private StorageActuals loadStorageActuals(LocalDate startDate, LocalDate endDate) {
        try {
            Map<DateRange, StorageWindow> windows = new HashMap<>();
            jdbc.query("""
                    with ranked_storage as (
                        select period_start, period_end, parent_sku, final_storage_fee, imported_at,
                               row_number() over (
                                   partition by period_start, period_end,
                                                coalesce(nullif(upper(trim(sku)), ''), concat('#', id))
                                   order by imported_at desc, id desc
                               ) as row_rank
                          from wm_ads_wfs_storage_fee
                         where period_start <= ? and period_end >= ?
                    )
                    select period_start, period_end, upper(trim(coalesce(parent_sku, ''))) as parent_sku,
                           coalesce(sum(final_storage_fee), 0) as fee, max(imported_at) as latest_imported_at
                      from ranked_storage
                     where row_rank = 1
                     group by period_start, period_end, upper(trim(coalesce(parent_sku, '')))
                    """, rs -> {
                DateRange period = new DateRange(rs.getDate("period_start").toLocalDate(),
                        rs.getDate("period_end").toLocalDate());
                if (period.start().isAfter(period.end())) return;
                StorageWindow window = windows.computeIfAbsent(period, StorageWindow::new);
                window.addFee(feeParent(rs.getString("parent_sku")), bd(rs.getBigDecimal("fee")),
                        rs.getTimestamp("latest_imported_at"));
            }, Date.valueOf(endDate), Date.valueOf(startDate));

            // Only one report period contributes to a given day. Prefer the most recently uploaded period.
            List<StorageWindow> ordered = windows.values().stream()
                    .sorted(Comparator.comparing(StorageWindow::latestImport,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparingInt(w -> w.period.days())
                            .thenComparing(w -> w.period.start(), Comparator.reverseOrder()))
                    .toList();
            Set<LocalDate> days = new LinkedHashSet<>();
            Map<String, BigDecimal> bySku = new HashMap<>();
            for (StorageWindow window : ordered) {
                int appliedDays = window.period.addDays(days, startDate, endDate);
                if (appliedDays == 0) continue;
                BigDecimal factor = BigDecimal.valueOf(appliedDays).divide(
                        BigDecimal.valueOf(window.period.days()), 12, RoundingMode.HALF_UP);
                window.fees.forEach((sku, fee) -> bySku.merge(sku, fee.multiply(factor), BigDecimal::add));
            }
            return new StorageActuals(bySku, new Coverage(days));
        } catch (DataAccessException ignored) {
            return new StorageActuals(Map.of(), Coverage.EMPTY);
        }
    }

    private Map<String, AssumptionCost> loadProfitAssumptions() {
        Map<String, AssumptionCost> map = new HashMap<>();
        try {
            jdbc.query("""
                    select upper(trim(parent_sku)) as parent_sku, purchase_cost, first_leg, delivery_fee,
                           refund_rate, prefer_manual_refund_rate
                      from wm_ads_profit_assumption
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, new AssumptionCost(
                            rs.getBigDecimal("purchase_cost"),
                            rs.getBigDecimal("first_leg"),
                            rs.getBigDecimal("delivery_fee"),
                            rs.getBigDecimal("refund_rate"),
                            rs.getBoolean("prefer_manual_refund_rate")
                    ));
                }
            });
        } catch (DataAccessException ignored) {
            // Optional fallback table
        }
        return map;
    }

    private Map<String, String> loadProductItemIds() {
        Map<String, String> map = new HashMap<>();
        jdbc.query("""
                select upper(trim(sku)) as seller_sku, min(trim(product_id)) as item_id
                  from wm_ads_product_record
                 where current_version = true and product_id is not null and trim(product_id) <> ''
                 group by upper(trim(sku))
                having count(distinct trim(product_id)) = 1
                """, rs -> {
            map.put(rs.getString("seller_sku"), rs.getString("item_id"));
        });
        return map;
    }

    private Map<String, BigDecimal> loadRefundRates() {
        try {
            Map<String, BigDecimal> result = new HashMap<>();
            refundRateService.ratesByParent().forEach((sku, rate) -> result.put(
                    sku.trim().toUpperCase(Locale.ROOT), rate));
            return result;
        } catch (DataAccessException ignored) {
            // Historical finance rates remain available when no comparable return tables exist.
            return Map.of();
        }
    }

    private Map<String, BigDecimal> loadProductDeliveryFees() {
        Map<String, BigDecimal> map = new HashMap<>();
        try {
            jdbc.query("""
                    select upper(trim(parent_sku)) as parent_sku, avg(delivery_fee) as fee
                      from wm_ads_product_record
                     where current_version = true and delivery_fee is not null and delivery_fee > 0
                     group by upper(trim(parent_sku))
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, bd(rs.getBigDecimal("fee")));
                }
            });
        } catch (DataAccessException ignored) {
            // Optional fallback table
        }
        return map;
    }

    private LocalDate queryMaxDate(String sql) {
        try {
            Date d = jdbc.queryForObject(sql, Date.class);
            return d == null ? null : d.toLocalDate();
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    static LocalDate resolveFinalPeriodEnd(String reportMonth, String settlementPeriod) {
        YearMonth ym = YearMonth.parse(reportMonth);
        if (settlementPeriod != null && !settlementPeriod.isBlank()) {
            Matcher m = PERIOD_END_MD.matcher(settlementPeriod.trim());
            if (m.find()) {
                int month = Integer.parseInt(m.group(1));
                int day = Integer.parseInt(m.group(2));
                int year = ym.getYear();
                if (ym.getMonthValue() == 12 && month == 1) year++;
                else if (ym.getMonthValue() == 1 && month == 12) year--;
                int maxDay = YearMonth.of(year, month).lengthOfMonth();
                return LocalDate.of(year, month, Math.min(day, maxDay));
            }
        }
        return ym.atEndOfMonth();
    }

    private static String formatPeriod(LocalDate start, LocalDate end) {
        return String.format("%d.%02d-%d.%02d",
                start.getMonthValue(), start.getDayOfMonth(),
                end.getMonthValue(), end.getDayOfMonth());
    }

    private static BigDecimal bd(Object val) {
        if (val == null) return ZERO;
        if (val instanceof BigDecimal b) return b;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(val.toString().trim());
        } catch (Exception ignored) {
            return ZERO;
        }
    }

    private static boolean isZero(BigDecimal v) {
        return v == null || v.abs().compareTo(new BigDecimal("0.000001")) < 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean validRate(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(ONE) <= 0;
    }

    private static String feeParent(String value) {
        return value == null || value.isBlank() ? UNASSIGNED_STYLE : value;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static BigDecimal safeDiv(BigDecimal num, BigDecimal den) {
        if (isZero(den)) return ZERO;
        return num.divide(den, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }

    private record DateRange(LocalDate start, LocalDate end) {
        int days() {
            return Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        }

        int addDays(Set<LocalDate> covered, LocalDate lower, LocalDate upper) {
            LocalDate last = earlier(end, upper);
            int before = covered.size();
            for (LocalDate day = later(start, lower); !day.isAfter(last); day = day.plusDays(1)) {
                covered.add(day);
            }
            return covered.size() - before;
        }
    }

    private record Coverage(Set<LocalDate> dates) {
        static final Coverage EMPTY = new Coverage(Set.of());

        boolean covers(LocalDate day) {
            return dates.contains(day);
        }

        int days() {
            return dates.size();
        }

    }

    private record SourceCutoffs(LocalDate orders, LocalDate ads, LocalDate wfs, LocalDate storage) {}

    private record OrderAgg(BigDecimal qty, BigDecimal revUsd, BigDecimal grossCommission, BigDecimal promotedQty,
                            BigDecimal qtyWithoutWfs, BigDecimal revWithoutStorage) {
        static final OrderAgg EMPTY = new OrderAgg(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);

        OrderAgg plus(OrderAgg other) {
            return new OrderAgg(qty.add(other.qty), revUsd.add(other.revUsd), grossCommission.add(other.grossCommission),
                    promotedQty.add(other.promotedQty), qtyWithoutWfs.add(other.qtyWithoutWfs),
                    revWithoutStorage.add(other.revWithoutStorage));
        }

        String commissionBasis() {
            if (qty.signum() == 0) return "NO_SALES";
            if (promotedQty.signum() == 0) return "STANDARD_TIER";
            return promotedQty.compareTo(qty) == 0 ? "ITEM_PROMOTION" : "ITEM_PROMOTION_MIX";
        }
    }

    private record AssumptionCost(BigDecimal purchaseCost, BigDecimal firstLeg, BigDecimal deliveryFee,
                                  BigDecimal refundRate, boolean preferManualRefundRate) {}
    private record RefundRates(BigDecimal amount, BigDecimal units, String basis) {}
    private record WfsSkuFee(BigDecimal wfsFeeUsd, BigDecimal reimbUsd, BigDecimal disposalUsd, BigDecimal otherUsd) {
        static final WfsSkuFee ZERO_FEE = new WfsSkuFee(ZERO, ZERO, ZERO, ZERO);
    }
    private record WfsActuals(Map<String, WfsSkuFee> bySku, Coverage coverage) {}
    private record StorageActuals(Map<String, BigDecimal> bySku, Coverage coverage) {}

    private static final class StorageWindow {
        final DateRange period;
        final Map<String, BigDecimal> fees = new HashMap<>();
        private Timestamp latestImport;

        StorageWindow(DateRange period) {
            this.period = period;
        }

        void addFee(String sku, BigDecimal fee, Timestamp uploadedAt) {
            fees.merge(sku, fee, BigDecimal::add);
            if (uploadedAt != null && (latestImport == null || uploadedAt.after(latestImport))) {
                latestImport = uploadedAt;
            }
        }

        Timestamp latestImport() {
            return latestImport;
        }
    }

    private static final class SkuAcc {
        int rawQty;
        BigDecimal rawRev = ZERO;
        BigDecimal wQty = ZERO;
        BigDecimal wRev = ZERO;
        BigDecimal wRefQty = ZERO;
        BigDecimal wRefUsd = ZERO;
        BigDecimal wWfsUsd = ZERO;
        BigDecimal wPurCny = ZERO;
        BigDecimal wFlCny = ZERO;
    }

    private record SkuCalibration(
            int qty,
            BigDecimal revUsd,
            BigDecimal refundAmountRate,
            BigDecimal unitRefundRate,
            BigDecimal wfsUnitFeeUsd,
            BigDecimal purchaseUnitCny,
            BigDecimal firstLegUnitCny
    ) {}

    private record Calibration(
            List<String> calibrationMonths,
            BigDecimal defaultExchangeRate,
            BigDecimal storeRefundAmountRate,
            BigDecimal storeUnitRefundRate,
            BigDecimal storeReturnRate,
            BigDecimal storeWfsUnitFeeUsd,
            BigDecimal storeStorageRate,
            BigDecimal storePurchaseRate,
            BigDecimal storeFirstLegRate,
            Map<String, SkuCalibration> bySku,
            Map<String, AssumptionCost> assumptions,
            Map<String, BigDecimal> productDeliveryFees,
            Map<String, BigDecimal> refundRatesBySku,
            Map<String, List<FinanceCommissionRuleService.Rule>> commissionRules,
            Map<String, String> itemsBySku
    ) {}
}
