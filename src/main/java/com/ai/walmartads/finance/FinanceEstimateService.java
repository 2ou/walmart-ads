package com.ai.walmartads.finance;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
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
 * (and {@code wm_ads_profit_assumption} / {@code wm_ads_product_record} fallbacks for new styles).</p>
 */
@Service
public class FinanceEstimateService {

    public static final String STAGE_ACTUAL = "ACTUAL";
    public static final String STAGE_COMPLETED = "COMPLETED_ESTIMATE";
    public static final String STAGE_IN_PROGRESS = "IN_PROGRESS_ESTIMATE";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_FX = new BigDecimal("6.8000");
    private static final BigDecimal NEW_SKU_RETURN_COGS_FACTOR = new BigDecimal("0.96");
    private static final BigDecimal NEW_SKU_RETURN_WFS_FACTOR = new BigDecimal("0.45");
    private static final int CYCLE_DAYS = 28;
    private static final Pattern PERIOD_END_MD = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\s*$");

    private final JdbcTemplate jdbc;

    public FinanceEstimateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            String costBasis
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
        LocalDate latestOrderDate = queryMaxDate("select max(order_date) from wm_ads_order_record_valid");
        if (latestOrderDate == null) {
            return List.of();
        }
        LocalDate latestAdDate = queryMaxDate("select max(report_date) from wm_ads_dashboard_ad_daily");
        LocalDate lastFinalEnd = resolveFinalPeriodEnd(latestFinalMonth, latestFinalSettlementPeriod);
        if (!latestOrderDate.isAfter(lastFinalEnd)) {
            return List.of();
        }

        Calibration calibration = buildCalibration(latestFinalExchangeRate);
        YearMonth baseYm = YearMonth.parse(latestFinalMonth);
        LocalDate cursorStart = lastFinalEnd.plusDays(1);
        List<EstimatedCycle> cycles = new ArrayList<>();
        int step = 1;

        while (!cursorStart.isAfter(latestOrderDate) && step <= 6) {
            String monthCode = baseYm.plusMonths(step).toString();
            LocalDate plannedEnd = cursorStart.plusDays(CYCLE_DAYS - 1L);
            boolean isTargetOverride = options != null
                    && monthCode.equals(options.targetEstimateMonth())
                    && options.customStartDate() != null
                    && options.customEndDate() != null;

            LocalDate effectiveStart = isTargetOverride ? options.customStartDate() : cursorStart;
            LocalDate effectiveEnd = isTargetOverride
                    ? options.customEndDate()
                    : (latestOrderDate.isBefore(plannedEnd) ? latestOrderDate : plannedEnd);
            if (effectiveStart.isAfter(effectiveEnd)) {
                LocalDate tmp = effectiveStart;
                effectiveStart = effectiveEnd;
                effectiveEnd = tmp;
            }

            boolean inProgress = !isTargetOverride && latestOrderDate.isBefore(plannedEnd);
            String stage = inProgress ? STAGE_IN_PROGRESS : STAGE_COMPLETED;
            int elapsedDays = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
            int plannedDays = isTargetOverride ? elapsedDays : CYCLE_DAYS;

            BigDecimal fx = (options != null && options.overrideExchangeRate() != null
                    && options.overrideExchangeRate().compareTo(ZERO) > 0)
                    ? options.overrideExchangeRate()
                    : calibration.defaultExchangeRate();
            BigDecimal offsiteCny = (options != null && options.overrideOffsiteFeeCny() != null
                    && (options.targetEstimateMonth() == null || monthCode.equals(options.targetEstimateMonth())))
                    ? options.overrideOffsiteFeeCny()
                    : ZERO;

            EstimatedCycle cycle = computeCycle(
                    monthCode,
                    accountCode,
                    companyName,
                    effectiveStart,
                    effectiveEnd,
                    plannedEnd,
                    elapsedDays,
                    plannedDays,
                    stage,
                    latestOrderDate,
                    latestAdDate,
                    calibration,
                    fx,
                    offsiteCny
            );
            if (cycle != null) {
                cycles.add(cycle);
            }

            cursorStart = plannedEnd.plusDays(1);
            step++;
        }
        return cycles;
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
            LocalDate latestOrderDate,
            LocalDate latestAdDate,
            Calibration cal,
            BigDecimal fx,
            BigDecimal offsiteTotalCny
    ) {
        Map<String, OrderAgg> ordersBySku = loadLiveOrders(startDate, endDate);
        Map<String, BigDecimal> adsBySku = loadLiveAdsBySku(startDate, endDate);
        BigDecimal totalLiveAds = loadLiveAdsTotal(startDate, endDate);

        if (ordersBySku.isEmpty() && isZero(totalLiveAds)) {
            return null;
        }

        // Auto-upgrade to exact WFS settlement/storage actuals if the user already uploaded them for this cycle
        WfsActuals wfsActual = loadWfsActualsIfCovered(startDate, endDate);
        Map<String, BigDecimal> storageActualBySku = loadStorageActualsIfCovered(startDate, endDate);
        boolean wfsActualUsed = wfsActual != null;
        boolean storageActualUsed = storageActualBySku != null && !storageActualBySku.isEmpty();

        Set<String> allSkus = new LinkedHashSet<>(ordersBySku.keySet());
        allSkus.addAll(adsBySku.keySet());
        if (wfsActualUsed) {
            allSkus.addAll(wfsActual.bySku().keySet());
        }

        BigDecimal totalRevUsd = ordersBySku.values().stream()
                .map(OrderAgg::revUsd)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal sumAttributedAds = adsBySku.values().stream().reduce(ZERO, BigDecimal::add);
        BigDecimal unattributedAds = totalLiveAds.subtract(sumAttributedAds);

        BigDecimal totalActualStorage = storageActualUsed
                ? storageActualBySku.values().stream().reduce(ZERO, BigDecimal::add)
                : ZERO;

        List<EstimatedStyle> styleRows = new ArrayList<>();
        BigDecimal sRevUsd = ZERO;
        BigDecimal sRefundUsd = ZERO;
        BigDecimal sCommUsd = ZERO;
        BigDecimal sWfsUsd = ZERO;
        BigDecimal sAdUsd = ZERO;
        BigDecimal sStorageUsd = ZERO;
        BigDecimal sReimbUsd = ZERO;
        BigDecimal sDispUsd = ZERO;
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
            OrderAgg o = ordersBySku.getOrDefault(sku, new OrderAgg(0, ZERO));
            int qty = o.qty();
            BigDecimal revUsd = o.revUsd();
            BigDecimal revShare = !isZero(totalRevUsd) ? revUsd.divide(totalRevUsd, 8, RoundingMode.HALF_UP) : ZERO;
            BigDecimal aspUsd = qty > 0 ? revUsd.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP) : ZERO;

            SkuCalibration sc = cal.bySku().get(sku);
            AssumptionCost pa = cal.assumptions().get(sku);
            BigDecimal prodDeliveryFee = cal.productDeliveryFees().get(sku);

            // 1. Refund rate & amount
            BigDecimal refAmtRate = (sc != null && sc.revUsd().compareTo(new BigDecimal("250")) >= 0)
                    ? sc.refundAmountRate()
                    : cal.storeRefundAmountRate();
            BigDecimal unitRefRate = (sc != null && sc.qty() >= 15)
                    ? sc.unitRefundRate()
                    : cal.storeUnitRefundRate();
            BigDecimal refundUsd = revUsd.multiply(refAmtRate).negate();
            int refundQty = BigDecimal.valueOf(qty).multiply(unitRefRate).setScale(0, RoundingMode.HALF_UP).intValue();
            int returnQty = BigDecimal.valueOf(qty).multiply(cal.storeReturnRate()).setScale(0, RoundingMode.HALF_UP).intValue();

            // 2. Commission USD (tier-aware when new style, else recency-weighted net commission rate)
            BigDecimal commRate;
            if (sc != null && sc.revUsd().compareTo(new BigDecimal("250")) >= 0) {
                commRate = sc.commissionRate();
            } else {
                BigDecimal baseTier = aspUsd.compareTo(new BigDecimal("20")) >= 0 ? new BigDecimal("0.15")
                        : (aspUsd.compareTo(new BigDecimal("15")) >= 0 ? new BigDecimal("0.10") : new BigDecimal("0.05"));
                commRate = baseTier.multiply(ONE.subtract(refAmtRate));
            }
            BigDecimal commUsd = revUsd.multiply(commRate).negate();

            // 3. WFS Fee, Reimbursements, Disposal (WFS actual if uploaded, else per-SKU finance unit rate)
            BigDecimal wfsUsd;
            BigDecimal reimbUsd;
            BigDecimal dispUsd;
            if (wfsActualUsed) {
                WfsSkuFee wf = wfsActual.bySku().getOrDefault(sku, WfsSkuFee.ZERO_FEE);
                wfsUsd = wf.wfsFeeUsd().add(wfsActual.unattributedWfsUsd().multiply(revShare));
                reimbUsd = wf.reimbUsd().add(wfsActual.unattributedReimbUsd().multiply(revShare));
                dispUsd = wf.disposalUsd().add(wfsActual.unattributedDispUsd().multiply(revShare));
            } else {
                BigDecimal wfsUnit;
                if (sc != null && sc.qty() >= 15) {
                    wfsUnit = sc.wfsUnitFeeUsd();
                } else if (prodDeliveryFee != null && prodDeliveryFee.compareTo(ZERO) > 0) {
                    wfsUnit = prodDeliveryFee.multiply(ONE.add(unitRefRate.multiply(NEW_SKU_RETURN_WFS_FACTOR)));
                } else if (pa != null && pa.deliveryFee() != null && pa.deliveryFee().compareTo(ZERO) > 0) {
                    wfsUnit = pa.deliveryFee().multiply(ONE.add(unitRefRate.multiply(NEW_SKU_RETURN_WFS_FACTOR)));
                } else {
                    wfsUnit = cal.storeWfsUnitFeeUsd();
                }
                wfsUsd = BigDecimal.valueOf(qty).multiply(wfsUnit).negate();
                reimbUsd = revUsd.multiply(cal.storeReimbRate());
                dispUsd = revUsd.multiply(cal.storeDispRate());
            }

            // 4. Live Ad Spend (exact SKU ad spend + finance-style revenue-share apportionment of any unattributed diff)
            BigDecimal skuAd = adsBySku.getOrDefault(sku, ZERO).add(unattributedAds.multiply(revShare));
            BigDecimal adUsd = skuAd.negate();

            // 5. Storage Fee (actual if uploaded, else revenue-rate estimate)
            BigDecimal storageUsd = storageActualUsed
                    ? storageActualBySku.getOrDefault(sku, ZERO).negate()
                    : revUsd.multiply(cal.storeStorageRate()).negate();
            if (storageActualUsed && !storageActualBySku.containsKey(sku) && isZero(totalActualStorage)) {
                storageUsd = ZERO;
            }

            // 6. Net Settlement USD & CNY
            BigDecimal netUsd = revUsd.add(refundUsd).add(commUsd).add(wfsUsd).add(adUsd)
                    .add(storageUsd).add(reimbUsd).add(dispUsd);
            BigDecimal netCny = netUsd.multiply(fx);

            // 7. Purchase Cost & First-Leg Cost CNY
            BigDecimal purCny;
            BigDecimal flCny;
            String costBasis;
            if (sc != null && sc.qty() >= 15 && sc.purchaseUnitCny().compareTo(ZERO) > 0) {
                purCny = BigDecimal.valueOf(qty).multiply(sc.purchaseUnitCny());
                flCny = BigDecimal.valueOf(qty).multiply(sc.firstLegUnitCny());
                costBasis = "FINANCE_HIST";
            } else if (pa != null && pa.purchaseCost() != null && pa.purchaseCost().compareTo(ZERO) > 0) {
                purCny = BigDecimal.valueOf(qty).multiply(pa.purchaseCost()).multiply(NEW_SKU_RETURN_COGS_FACTOR);
                flCny = BigDecimal.valueOf(qty).multiply(pa.firstLeg() == null ? ZERO : pa.firstLeg()).multiply(NEW_SKU_RETURN_COGS_FACTOR);
                costBasis = "PROFIT_ASSUMPTION";
            } else {
                BigDecimal revCny = revUsd.multiply(fx);
                purCny = revCny.multiply(cal.storePurchaseRate());
                flCny = revCny.multiply(cal.storeFirstLegRate());
                costBasis = "STORE_FALLBACK";
            }

            // 8. Optional user-entered offsite fee apportioned by revenue share
            BigDecimal skuOffsiteCny = offsiteTotalCny.multiply(revShare);
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
                    ZERO,
                    scale2(netUsd),
                    scale2(netCny),
                    scale2(purCny),
                    scale2(flCny),
                    scale2(skuOffsiteCny),
                    scale2(profitCny),
                    costBasis
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
                ZERO,
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
                latestOrderDate,
                latestAdDate,
                cal.calibrationMonths(),
                scale4(cal.defaultExchangeRate()),
                scale4(fx),
                scale2(offsiteTotalCny),
                wfsActualUsed,
                storageActualUsed,
                store,
                styleRows
        );
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
        BigDecimal wReimb = ZERO, wDisp = ZERO, wPur = ZERO, wFl = ZERO;
        BigDecimal wQty = ZERO, wRefQty = ZERO, wRetQty = ZERO;

        if (!chronological.isEmpty()) {
            String inClause = String.join(",", chronological.stream().map(m -> "'" + m + "'").toList());
            List<Map<String, Object>> storeRows = jdbc.queryForList(
                    "select report_month, exchange_rate, sales_revenue_usd, refund_usd, wfs_fee_usd, "
                            + "storage_fee_usd, inventory_reimbursement_usd, inventory_disposal_usd, "
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
                wReimb = wReimb.add(bd(r.get("inventory_reimbursement_usd")).multiply(w));
                wDisp = wDisp.add(bd(r.get("inventory_disposal_usd")).multiply(w));
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
                            + "commission_usd, wfs_fee_usd, purchase_cost_cny, first_leg_cost_cny "
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
                acc.wCommUsd = acc.wCommUsd.add(bd(r.get("commission_usd")).abs().multiply(w));
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
            BigDecimal commRate = safeDiv(a.wCommUsd, a.wRev);
            BigDecimal wfsUnit = safeDiv(a.wWfsUsd, a.wQty);
            BigDecimal purUnit = safeDiv(a.wPurCny, a.wQty);
            BigDecimal flUnit = safeDiv(a.wFlCny, a.wQty).max(ZERO);
            bySku.put(entry.getKey(), new SkuCalibration(
                    a.rawQty, a.rawRev, refAmtRate, unitRefRate, commRate, wfsUnit, purUnit, flUnit));
        }

        Map<String, AssumptionCost> assumptions = loadProfitAssumptions();
        Map<String, BigDecimal> productDeliveryFees = loadProductDeliveryFees();

        BigDecimal fx = (latestFinalExchangeRate != null && latestFinalExchangeRate.compareTo(ZERO) > 0)
                ? latestFinalExchangeRate : DEFAULT_FX;

        return new Calibration(
                chronological,
                fx,
                safeDiv(wRefUsd, wRev),
                safeDiv(wRefQty, wQty),
                safeDiv(wRetQty, wQty),
                safeDiv(wWfs, wQty),
                safeDiv(wSto, wRev),
                safeDiv(wReimb, wRev),
                safeDiv(wDisp, wRev),
                safeDiv(wPur, wRevCny),
                safeDiv(wFl, wRevCny),
                bySku,
                assumptions,
                productDeliveryFees
        );
    }

    private Map<String, OrderAgg> loadLiveOrders(LocalDate startDate, LocalDate endDate) {
        Map<String, OrderAgg> map = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    select upper(trim(parent_sku)) as parent_sku,
                           coalesce(sum(quantity), 0) as qty,
                           coalesce(sum(item_cost), 0) as rev_usd
                      from wm_ads_order_record_valid
                     where order_date between ? and ?
                       and parent_sku is not null and trim(parent_sku) <> ''
                     group by upper(trim(parent_sku))
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, new OrderAgg(
                            bd(rs.getBigDecimal("qty")).setScale(0, RoundingMode.HALF_UP).intValue(),
                            bd(rs.getBigDecimal("rev_usd"))
                    ));
                }
            }, Date.valueOf(startDate), Date.valueOf(endDate));
        } catch (DataAccessException ignored) {
            // Minimal test schemas may not include order tables
        }
        return map;
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

    private WfsActuals loadWfsActualsIfCovered(LocalDate startDate, LocalDate endDate) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select upper(trim(coalesce(parent_sku, ''))) as parent_sku,
                           transaction_type,
                           coalesce(sum(net_payable), 0) as net_payable
                      from wm_ads_wfs_fulfillment_fee
                     where period_start <= ? and period_end >= ?
                     group by upper(trim(coalesce(parent_sku, ''))), transaction_type
                    """, Date.valueOf(startDate.plusDays(2)), Date.valueOf(endDate.minusDays(2)));
            if (rows.isEmpty()) return null;

            Map<String, BigDecimal[]> bySkuRaw = new HashMap<>();
            BigDecimal unWfs = ZERO, unReimb = ZERO, unDisp = ZERO;
            for (Map<String, Object> r : rows) {
                String sku = (String) r.get("parent_sku");
                String type = String.valueOf(r.get("transaction_type"));
                BigDecimal payable = bd(r.get("net_payable")); // positive = fee charged to seller, negative = credit to seller
                BigDecimal signedPnl = payable.negate();

                int slot;
                if ("FulfillmentFee".equalsIgnoreCase(type) || "ReturnProcessingFee".equalsIgnoreCase(type)) {
                    slot = 0; // wfsFeeUsd
                } else if ("LostInventory".equalsIgnoreCase(type) || "FoundInventory".equalsIgnoreCase(type)
                        || "Refund".equalsIgnoreCase(type) || "Charge".equalsIgnoreCase(type)) {
                    slot = 1; // reimbUsd
                } else if ("RC_InventoryDisposalFee".equalsIgnoreCase(type)) {
                    slot = 2; // dispUsd
                } else {
                    continue;
                }

                if (sku == null || sku.isBlank()) {
                    if (slot == 0) unWfs = unWfs.add(signedPnl);
                    else if (slot == 1) unReimb = unReimb.add(signedPnl);
                    else unDisp = unDisp.add(signedPnl);
                } else {
                    BigDecimal[] arr = bySkuRaw.computeIfAbsent(sku, ignored -> new BigDecimal[]{ZERO, ZERO, ZERO});
                    arr[slot] = arr[slot].add(signedPnl);
                }
            }
            Map<String, WfsSkuFee> bySku = new HashMap<>();
            bySkuRaw.forEach((k, v) -> bySku.put(k, new WfsSkuFee(v[0], v[1], v[2])));
            return new WfsActuals(bySku, unWfs, unReimb, unDisp);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private Map<String, BigDecimal> loadStorageActualsIfCovered(LocalDate startDate, LocalDate endDate) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select upper(trim(coalesce(parent_sku, ''))) as parent_sku,
                           coalesce(sum(final_storage_fee), 0) as fee
                      from wm_ads_wfs_storage_fee
                     where period_start <= ? and period_end >= ?
                     group by upper(trim(coalesce(parent_sku, '')))
                    """, Date.valueOf(startDate.plusDays(2)), Date.valueOf(endDate.minusDays(2)));
            if (rows.isEmpty()) return null;
            Map<String, BigDecimal> map = new HashMap<>();
            for (Map<String, Object> r : rows) {
                String sku = (String) r.get("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, bd(r.get("fee")));
                }
            }
            return map;
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private Map<String, AssumptionCost> loadProfitAssumptions() {
        Map<String, AssumptionCost> map = new HashMap<>();
        try {
            jdbc.query("""
                    select upper(trim(parent_sku)) as parent_sku, purchase_cost, first_leg, delivery_fee
                      from wm_ads_profit_assumption
                    """, rs -> {
                String sku = rs.getString("parent_sku");
                if (sku != null && !sku.isBlank()) {
                    map.put(sku, new AssumptionCost(
                            rs.getBigDecimal("purchase_cost"),
                            rs.getBigDecimal("first_leg"),
                            rs.getBigDecimal("delivery_fee")
                    ));
                }
            });
        } catch (DataAccessException ignored) {
            // Optional fallback table
        }
        return map;
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

    private record OrderAgg(int qty, BigDecimal revUsd) {}
    private record AssumptionCost(BigDecimal purchaseCost, BigDecimal firstLeg, BigDecimal deliveryFee) {}
    private record WfsSkuFee(BigDecimal wfsFeeUsd, BigDecimal reimbUsd, BigDecimal disposalUsd) {
        static final WfsSkuFee ZERO_FEE = new WfsSkuFee(ZERO, ZERO, ZERO);
    }
    private record WfsActuals(Map<String, WfsSkuFee> bySku, BigDecimal unattributedWfsUsd,
                              BigDecimal unattributedReimbUsd, BigDecimal unattributedDispUsd) {}

    private static final class SkuAcc {
        int rawQty;
        BigDecimal rawRev = ZERO;
        BigDecimal wQty = ZERO;
        BigDecimal wRev = ZERO;
        BigDecimal wRefQty = ZERO;
        BigDecimal wRefUsd = ZERO;
        BigDecimal wCommUsd = ZERO;
        BigDecimal wWfsUsd = ZERO;
        BigDecimal wPurCny = ZERO;
        BigDecimal wFlCny = ZERO;
    }

    private record SkuCalibration(
            int qty,
            BigDecimal revUsd,
            BigDecimal refundAmountRate,
            BigDecimal unitRefundRate,
            BigDecimal commissionRate,
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
            BigDecimal storeReimbRate,
            BigDecimal storeDispRate,
            BigDecimal storePurchaseRate,
            BigDecimal storeFirstLegRate,
            Map<String, SkuCalibration> bySku,
            Map<String, AssumptionCost> assumptions,
            Map<String, BigDecimal> productDeliveryFees
    ) {}
}
