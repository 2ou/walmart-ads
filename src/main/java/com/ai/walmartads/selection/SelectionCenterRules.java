package com.ai.walmartads.selection;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class SelectionCenterRules {
    static final String RULE_VERSION = "inventory-delta-v2";

    record Group(String key, boolean verified, SelectionSnapshot.Item item, String representative,
                 int expected, List<SelectionSnapshot.Variant> variants) {}
    record Delta(Integer previousQuantity, Integer currentQuantity, Long sales, Long stockIncrease,
                 boolean comparable, String reason) {}

    private SelectionCenterRules() {}

    static void validate(SelectionSnapshot snapshot) {
        URI source = uri(snapshot.sourceUrl(), 4096, "新品页链接");
        check("www.walmart.com".equalsIgnoreCase(source.getHost())
                && "/shop/clothing-and-accessories/womens-new-arrivals".equals(source.getPath()),
                "请使用 Walmart 女装新品页链接");
        check(snapshot.zip() != null && snapshot.zip().matches("[0-9]{5}"), "ZIP 邮编须为五位数字");
        check(snapshot.date() != null, "缺少榜单日期");
        check(snapshot.expectedPages() > 0 && snapshot.completedPages() >= 0
                && snapshot.completedPages() <= snapshot.expectedPages(), "候选页覆盖无效");
        check(!snapshot.complete() || snapshot.completedPages() == snapshot.expectedPages(), "完整批次须覆盖所有候选页");
        check(!snapshot.inventoryComplete() || snapshot.complete(), "未完成候选页扫描的批次无法标记库存采完");
        check(snapshot.items() != null && !snapshot.items().isEmpty() && snapshot.items().size() <= 20_000,
                "候选清单为空或过大");
        check(snapshot.declaredCount() == null || snapshot.declaredCount() >= 0, "结果总数不能为负数");
        text(snapshot.issue(), 600, "采集说明");
    }

    static List<Group> groups(SelectionSnapshot snapshot) {
        List<SelectionSnapshot.Item> inputs = new ArrayList<>(snapshot.items());
        for (SelectionSnapshot.Item item : inputs) validateItem(item);
        inputs.sort(Comparator.comparing(SelectionSnapshot.Item::sourcePosition, Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, String> membership = new HashMap<>();
        for (SelectionSnapshot.Item item : inputs) {
            if (!present(item.styleKey())) continue;
            claim(membership, item.itemId(), item.styleKey());
            if (present(item.primaryItemId())) claim(membership, item.primaryItemId(), item.styleKey());
            for (SelectionSnapshot.Variant variant : variants(item)) claim(membership, variant.itemId(), item.styleKey());
        }
        Map<String, DraftGroup> drafts = new LinkedHashMap<>();
        for (SelectionSnapshot.Item item : inputs) {
            String verifiedKey = membership.get(item.itemId());
            String key = verifiedKey == null ? "item:" + item.itemId() : "group:" + verifiedKey;
            DraftGroup draft = drafts.computeIfAbsent(key, ignored -> new DraftGroup(item, verifiedKey != null));
            draft.add(item);
        }
        List<Group> result = new ArrayList<>();
        Map<String, String> variantOwners = new HashMap<>();
        for (Map.Entry<String, DraftGroup> entry : drafts.entrySet()) {
            DraftGroup draft = entry.getValue();
            List<SelectionSnapshot.Variant> members = List.copyOf(draft.variants.values());
            for (SelectionSnapshot.Variant member : members) claim(variantOwners, member.itemId(), entry.getKey());
            int expected = Math.max(draft.expected, members.size());
            if (snapshot.inventoryComplete()) {
                check(expected > 0 && members.size() >= expected && members.stream().allMatch(v -> present(v.availability())),
                        "仍有 SKU 缺少观测状态，不能将库存标记为已采完：" + draft.representative);
            }
            result.add(new Group(entry.getKey(), draft.verified, draft.item, draft.representative, expected, members));
        }
        return result;
    }

    private static void claim(Map<String, String> owners, String itemId, String key) {
        String other = owners.putIfAbsent(itemId, key);
        check(other == null || other.equals(key), "Item ID 同时出现在不同款式中，请核对：" + itemId);
    }

    private static final class DraftGroup {
        private final SelectionSnapshot.Item item;
        private final boolean verified;
        private final Map<String, SelectionSnapshot.Variant> variants = new LinkedHashMap<>();
        private String representative;
        private boolean representativeConfirmed;
        private int expected;

        private DraftGroup(SelectionSnapshot.Item item, boolean verified) {
            this.item = item;
            this.verified = verified;
            this.representative = item.itemId();
        }

        private void add(SelectionSnapshot.Item next) {
            expected = Math.max(expected, next.expectedSkus() == null ? 0 : next.expectedSkus());
            if (verified && present(next.styleKey()) && present(next.primaryItemId())) {
                if (!representativeConfirmed) representative = next.primaryItemId();
                representativeConfirmed = true;
            }
            for (SelectionSnapshot.Variant variant : variants(next)) {
                variants.merge(variant.itemId(), variant, SelectionCenterRules::preferObservation);
            }
        }
    }

    private static SelectionSnapshot.Variant preferObservation(SelectionSnapshot.Variant left, SelectionSnapshot.Variant right) {
        if (quantity(left) == null && quantity(right) != null) return right;
        if (quantity(right) == null && quantity(left) != null) return left;
        if (right.observedAt() != null && (left.observedAt() == null || right.observedAt().isAfter(left.observedAt()))) return right;
        return left;
    }

    private static void validateItem(SelectionSnapshot.Item item) {
        check(item != null, "候选商品不能为空");
        itemId(item.itemId());
        if (present(item.primaryItemId())) itemId(item.primaryItemId());
        text(item.productId(), 64, "商品 Product ID");
        text(item.styleKey(), 120, "款式标识");
        check(present(item.title()), "商品标题为空：" + item.itemId());
        text(item.title(), 1000, "商品标题");
        text(item.brand(), 200, "品牌");
        text(item.category(), 200, "分类");
        if (present(item.imageUrl())) {
            URI picture = uri(item.imageUrl(), 4096, "主图 URL");
            String host = picture.getHost().toLowerCase(Locale.ROOT);
            check(host.equals("walmartimages.com") || host.endsWith(".walmartimages.com")
                            || host.equals("walmart.com") || host.endsWith(".walmart.com"),
                    "仅接受 Walmart 主图 URL");
        }
        check(item.price() == null || (item.price().signum() >= 0 && item.price().compareTo(java.math.BigDecimal.valueOf(9_999_999_999L)) <= 0),
                "价格超出允许范围：" + item.itemId());
        check(item.sourcePosition() == null || item.sourcePosition() > 0, "候选页原始位置必须为正数");
        check(item.expectedSkus() == null || item.expectedSkus() >= 0, "预期 SKU 数量不能为负数");
        for (SelectionSnapshot.Variant variant : variants(item)) validateVariant(variant);
    }

    private static void validateVariant(SelectionSnapshot.Variant variant) {
        check(variant != null, "SKU 记录不能为空");
        itemId(variant.itemId());
        text(variant.productId(), 64, "SKU Product ID");
        text(variant.color(), 200, "颜色");
        text(variant.size(), 128, "尺码");
        text(variant.availability(), 64, "在售状态");
        text(variant.sellerId(), 128, "卖家 ID");
        text(variant.offerId(), 128, "报价 ID");
        text(variant.fulfillment(), 64, "履约方式");
        check((variant.orderLimit() == null || variant.orderLimit() >= 0)
                && (variant.maxOrderQuantity() == null || variant.maxOrderQuantity() >= 0), "库存读数不能为负数");
    }

    private static URI uri(String value, int length, String label) {
        text(value, length, label);
        try {
            URI url = URI.create(Objects.requireNonNullElse(value, ""));
            check("https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                    && url.getRawUserInfo() == null && url.getPort() == -1, label + "须为 HTTPS 链接");
            return url;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(label + "须为有效的 HTTPS 链接");
        }
    }

    static void itemId(String value) {
        check(value != null && value.matches("[0-9]{1,32}"), "Item ID 须为数字");
    }

    private static void text(String value, int limit, String name) {
        check(value == null || value.length() <= limit, name + "长度超限");
    }

    private static List<SelectionSnapshot.Variant> variants(SelectionSnapshot.Item item) {
        return item.variants() == null ? List.of() : item.variants();
    }

    static Delta compare(SelectionSnapshot.Variant previous, SelectionSnapshot.Variant current) {
        Integer before = quantity(previous);
        Integer now = quantity(current);
        if (previous == null || current == null) {
            return new Delta(before, now, null, null, false, "缺少相邻日期的 SKU 记录");
        }
        if (before == null || now == null) {
            return new Delta(before, now, null, null, false, "可售上限缺失或读数冲突");
        }
        if (!sameConfirmed(previous.sellerId(), current.sellerId()) || !sameConfirmed(previous.offerId(), current.offerId())
                || !sameConfirmed(previous.fulfillment(), current.fulfillment())) {
            return new Delta(before, now, null, null, false, "卖家、报价或履约方式变更或未确认");
        }
        long movement = (long) now - before;
        return new Delta(before, now, Math.max(0, -movement), Math.max(0, movement), true, null);
    }

    private static Integer quantity(SelectionSnapshot.Variant variant) {
        return variant == null ? null : quantity(variant.orderLimit(), variant.maxOrderQuantity());
    }

    static Integer quantity(Integer limit, Integer max) {
        if (limit != null && max != null && !limit.equals(max)) return null;
        return max == null ? limit : max;
    }

    private static boolean sameConfirmed(String a, String b) {
        return present(a) && a.equals(b);
    }

    static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
