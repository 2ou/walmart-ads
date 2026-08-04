package com.ai.walmartads.product.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 依据 PNK 毛利估算表（工具表）的公式计算单 SKU 的测试毛利与测试毛利率。
 *
 * 输入：售价 B、单件配送费 F（来自 ItemReport 重量推算）、采购成本 G、头程 H、销量 C、
 *       总广告费 I、退款率 D、汇率 Q（默认 7，原币→人民币）。
 * 派生：佣金 J = ROUND(B × 档位, 2)，档位 B≥20→15% / 15≤B<20→10% / B<15→5%；仓储 K = B × 3%。
 * 输出：测试毛利(¥) L = (B − B·D − F − J − K)·Q·C − I·Q − (G+H)·C + C·D·0.7·(G+H)
 *       测试毛利率 M = L / (B·C) / Q
 * VAT 税率已在业务确认中去掉（按 0 处理）。
 */
@Component
public class ProfitCalculator {

    public static final BigDecimal EXCHANGE_RATE_DEFAULT = new BigDecimal("7");
    private static final BigDecimal AD_COST_DEFAULT = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal COMMISSION_TIER_HIGH = new BigDecimal("0.15");
    private static final BigDecimal COMMISSION_TIER_MID = new BigDecimal("0.10");
    private static final BigDecimal COMMISSION_TIER_LOW = new BigDecimal("0.05");
    private static final BigDecimal COMMISSION_THRESHOLD_HIGH = new BigDecimal("20");
    private static final BigDecimal COMMISSION_THRESHOLD_LOW = new BigDecimal("15");
    private static final BigDecimal STORAGE_RATE = new BigDecimal("0.03");
    private static final BigDecimal REFUND_COGS_FACTOR = new BigDecimal("0.7");

    public ProfitResult compute(
            BigDecimal price,
            BigDecimal deliveryFee,
            BigDecimal purchaseCost,
            BigDecimal firstLeg,
            BigDecimal salesQty,
            BigDecimal adCost,
            BigDecimal refundRate,
            BigDecimal exchangeRate
    ) {
        if (price == null || deliveryFee == null || purchaseCost == null || firstLeg == null || salesQty == null) {
            return null;
        }
        BigDecimal b = price;
        BigDecimal f = deliveryFee;
        BigDecimal g = purchaseCost;
        BigDecimal h = firstLeg;
        BigDecimal c = salesQty;
        BigDecimal i = adCost != null ? adCost : AD_COST_DEFAULT;
        BigDecimal d = refundRate != null ? refundRate : ZERO;
        BigDecimal q = exchangeRate != null ? exchangeRate : EXCHANGE_RATE_DEFAULT;

        BigDecimal commissionRate = commissionRate(b);
        BigDecimal j = b.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal k = b.multiply(STORAGE_RATE).setScale(6, RoundingMode.HALF_UP);

        BigDecimal unitNet = b.subtract(b.multiply(d)).subtract(f).subtract(j).subtract(k);
        BigDecimal profit = unitNet.multiply(q).multiply(c)
                .subtract(i.multiply(q))
                .subtract(g.add(h).multiply(c))
                .add(c.multiply(d).multiply(g.add(h)).multiply(REFUND_COGS_FACTOR));
        profit = profit.setScale(2, RoundingMode.HALF_UP);

        BigDecimal revenue = b.multiply(c).multiply(q);
        BigDecimal margin = revenue.signum() == 0 ? ZERO : profit.divide(revenue, 6, RoundingMode.HALF_UP);

        return new ProfitResult(profit, margin, j, k);
    }

    private static BigDecimal commissionRate(BigDecimal price) {
        if (price.compareTo(COMMISSION_THRESHOLD_HIGH) >= 0) {
            return COMMISSION_TIER_HIGH;
        }
        if (price.compareTo(COMMISSION_THRESHOLD_LOW) < 0) {
            return COMMISSION_TIER_LOW;
        }
        return COMMISSION_TIER_MID;
    }

    public record ProfitResult(BigDecimal profit, BigDecimal margin, BigDecimal commission, BigDecimal storage) {
    }
}
