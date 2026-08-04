package com.ai.walmartads.importing.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 依据 walmart-sku-fee-weight-match 技能反算沃尔玛 WFS 单件配送费。
 *
 * 计费重量：billing = ceil((shippingWeightLb + 0.25) * 16) / 16 （+0.25lb 包装增量，1/16 盎司粒度）。
 * 基础费按计费重量分段，再叠加服装附加（+0.50，类目含 Clothing/Apparel/Shoes）与低价附加（+1.00，零售价 < $10）。
 */
@Component
public class DeliveryFeeCalculator {

    private static final double LB_TO_KG = 0.45359237;
    private static final BigDecimal LOW_PRICE_THRESHOLD = new BigDecimal("10.00");

    public DeliveryFee compute(BigDecimal shippingWeightLb, BigDecimal price, String productCategory) {
        double weightLb = shippingWeightLb == null ? 0.0 : shippingWeightLb.doubleValue();
        double billing = Math.ceil((weightLb + 0.25) * 16.0) / 16.0;
        double base = baseFee(billing);
        boolean apparel = isApparel(productCategory);
        boolean lowPrice = price != null && price.compareTo(LOW_PRICE_THRESHOLD) < 0;
        double apparelAdd = apparel ? 0.50 : 0.0;
        double lowAdd = lowPrice ? 1.00 : 0.0;
        double total = base + apparelAdd + lowAdd;
        return new DeliveryFee(
                round6(BigDecimal.valueOf(billing)),
                round6(BigDecimal.valueOf(weightLb * LB_TO_KG)),
                money(base),
                money(apparelAdd),
                money(lowAdd),
                money(total)
        );
    }

    private static double baseFee(double w) {
        if (w <= 1.0) {
            return 3.45;
        }
        if (w <= 2.0) {
            return 4.95;
        }
        if (w <= 3.0) {
            return 5.45;
        }
        if (w <= 4.0) {
            return 5.75;
        }
        if (w <= 20.0) {
            return 5.75 + 0.40 * (w - 4.0);
        }
        if (w <= 21.0) {
            return 13.35;
        }
        if (w <= 30.0) {
            return 15.55 + 0.40 * (w - 21.0);
        }
        if (w <= 50.0) {
            return 14.55 + 0.40 * (w - 31.0);
        }
        return 17.55 + 0.40 * (w - 51.0);
    }

    private static boolean isApparel(String category) {
        if (category == null) {
            return false;
        }
        String normalized = category.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("clothing") || normalized.contains("apparel") || normalized.contains("shoes");
    }

    private static BigDecimal round6(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public record DeliveryFee(
            BigDecimal billingWeightLb,
            BigDecimal shippingWeightKg,
            BigDecimal baseFee,
            BigDecimal apparelSurcharge,
            BigDecimal lowPriceSurcharge,
            BigDecimal deliveryFee
    ) {
    }
}
