package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds an exact-SKU, full-catalog product import plan shared by preview and commit. */
@Component
public class ProductImportPlanner {

    private static final TypeReference<LinkedHashMap<String, Object>> RAW_MAP = new TypeReference<>() { };
    private static final Set<String> MANAGED_RAW_FIELDS = Set.of(
            "price", "strikethroughPrice", "upc", "gtin", "listingStatus", "store",
            "wfsAvailable", "nonWfsAvailable", "buyBoxPrice", "reviewCount", "averageRating",
            "competitorPrice", "fulfillmentType", "fulfillmentTypeName", "listingStartTime",
            "offerEndDate", "itemUrl", "promotionDataList", "promotionJson", "lastSyncedAt",
            "deliveryFee", "launchDate", "cuttingYield"
    );
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("sku", "SKU"),
            Map.entry("specProductType", "商品类型"),
            Map.entry("productId", "商品 ID"),
            Map.entry("productIdType", "商品 ID 类型"),
            Map.entry("productName", "商品名称"),
            Map.entry("brand", "品牌"),
            Map.entry("shortDescription", "简短描述"),
            Map.entry("keyFeatures", "关键卖点"),
            Map.entry("mainImageUrl", "主图"),
            Map.entry("productSecondaryImageURL", "副图"),
            Map.entry("color", "颜色"),
            Map.entry("colorCategory", "颜色类目"),
            Map.entry("clothingSize", "尺码"),
            Map.entry("clothingSizeGroup", "尺码组"),
            Map.entry("pattern", "图案"),
            Map.entry("materialName", "材质"),
            Map.entry("gender", "适用人群"),
            Map.entry("variantGroupId", "变体组 ID")
    );

    private final ProductRecordRepository productRepository;
    private final ObjectMapper objectMapper;

    public ProductImportPlanner(ProductRecordRepository productRepository, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
    }

    public Plan plan(ParsedRows<ProductRecord> parsed) {
        if (parsed == null || parsed.rows() == null || parsed.rows().isEmpty()) {
            throw new IllegalArgumentException("产品表没有可识别的 SKU 数据");
        }
        List<ProductRecord> current = productRepository.findAllByCurrentVersionTrueOrderBySkuAsc();
        Set<String> uploadedSkus = parsed.rows().stream().map(ProductRecord::getSku)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ProductRecord> history = uploadedSkus.isEmpty()
                ? List.of()
                : productRepository.findAllBySkuInOrderByCreatedAtDescIdDesc(uploadedSkus);
        return plan(parsed, current, history);
    }

    Plan plan(ParsedRows<ProductRecord> parsed, List<ProductRecord> current, List<ProductRecord> history) {
        Map<String, ProductRecord> currentBySku = current.stream().collect(Collectors.toMap(
                ProductRecord::getSku, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        Map<String, ProductRecord> latestBySku = new LinkedHashMap<>();
        history.forEach(row -> latestBySku.putIfAbsent(row.getSku(), row));

        Map<String, List<ProductRecord>> grouped = parsed.rows().stream().collect(Collectors.groupingBy(
                ProductRecord::getSku, LinkedHashMap::new, Collectors.toList()));
        List<Conflict> conflicts = new ArrayList<>();
        List<ProductRecord> unique = new ArrayList<>();
        for (Map.Entry<String, List<ProductRecord>> entry : grouped.entrySet()) {
            List<ProductRecord> duplicates = entry.getValue();
            ProductRecord first = duplicates.getFirst();
            Map<String, Object> firstRaw = uploadRaw(first);
            Set<String> conflictFields = new LinkedHashSet<>();
            for (int index = 1; index < duplicates.size(); index++) {
                conflictFields.addAll(differingFields(firstRaw, uploadRaw(duplicates.get(index))));
            }
            if (!conflictFields.isEmpty()) {
                conflicts.add(new Conflict(
                        entry.getKey(),
                        duplicates.stream().map(ProductRecord::getSourceRowNumber).sorted().toList(),
                        conflictFields.stream().map(this::label).toList()
                ));
            } else {
                unique.add(first);
            }
        }

        Set<String> incomingSkus = grouped.keySet();
        List<PlannedChange> changes = new ArrayList<>();
        List<ProductRecord> resolved = new ArrayList<>();
        for (ProductRecord candidate : unique) {
            ProductRecord active = currentBySku.get(candidate.getSku());
            ProductRecord previous = active == null ? latestBySku.get(candidate.getSku()) : active;
            Map<String, Object> before = previous == null ? Map.of() : raw(previous);
            Map<String, Object> after = new LinkedHashMap<>(before);
            after.putAll(uploadRaw(candidate));
            String mergedRaw = write(after);
            candidate.mergeProductUploadFrom(previous, mergedRaw);
            resolved.add(candidate);

            boolean restored = active == null && previous != null;
            ChangeType type = active == null ? ChangeType.ADD
                    : before.equals(after) ? ChangeType.UNCHANGED : ChangeType.UPDATE;
            changes.add(change(type, active == null ? previous : active, candidate, restored, false,
                    type == ChangeType.UPDATE ? fieldChanges(before, after)
                            : type == ChangeType.ADD ? fieldChanges(Map.of(), after) : List.of()));
        }

        List<ProductRecord> retired = current.stream()
                .filter(row -> !incomingSkus.contains(row.getSku()))
                .sorted(Comparator.comparing(ProductRecord::getSku))
                .toList();
        Map<String, Long> currentByParent = current.stream().collect(Collectors.groupingBy(
                row -> safeParent(row.getParentSku()), Collectors.counting()));
        Map<String, Long> retiredByParent = retired.stream().collect(Collectors.groupingBy(
                row -> safeParent(row.getParentSku()), Collectors.counting()));
        for (ProductRecord row : retired) {
            String parent = safeParent(row.getParentSku());
            boolean wholeStyle = Objects.equals(currentByParent.get(parent), retiredByParent.get(parent));
            changes.add(change(ChangeType.RETIRE, row, null, false, wholeStyle, List.of(
                    new FieldChange("status", "产品状态", "在售", "已停用")
            )));
        }

        changes.sort(Comparator.comparing((PlannedChange item) -> item.type().ordinal())
                .thenComparing(PlannedChange::sku));
        return new Plan(parsed.sourceRowCount(), unique.size(), List.copyOf(resolved), List.copyOf(changes),
                List.copyOf(conflicts), catalogFingerprint(current));
    }

    public String currentCatalogFingerprint() {
        return catalogFingerprint(productRepository.findAllByCurrentVersionTrueOrderBySkuAsc());
    }

    public String fieldsJson(PlannedChange change) {
        return write(Map.of("fields", change.fields()));
    }

    private PlannedChange change(
            ChangeType type,
            ProductRecord before,
            ProductRecord after,
            boolean restored,
            boolean wholeStyle,
            List<FieldChange> fields
    ) {
        ProductRecord display = after == null ? before : after;
        return new PlannedChange(type, display.getSku(), display.getParentSku(), display.getProductName(),
                display.getColor(), display.getSize(), restored, wholeStyle, fields);
    }

    private List<FieldChange> fieldChanges(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream()
                .filter(key -> !MANAGED_RAW_FIELDS.contains(key))
                .filter(key -> !Objects.equals(before.get(key), after.get(key)))
                .map(key -> new FieldChange(key, label(key), display(before.get(key)), display(after.get(key))))
                .toList();
    }

    private Set<String> differingFields(Map<String, Object> first, Map<String, Object> other) {
        Set<String> keys = new LinkedHashSet<>(first.keySet());
        keys.addAll(other.keySet());
        keys.removeIf(key -> Objects.equals(first.get(key), other.get(key)));
        return keys;
    }

    private Map<String, Object> uploadRaw(ProductRecord record) {
        Map<String, Object> values = raw(record);
        MANAGED_RAW_FIELDS.forEach(values::remove);
        return values;
    }

    private LinkedHashMap<String, Object> raw(ProductRecord record) {
        try {
            if (record == null || record.getRawPayload() == null || record.getRawPayload().isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(record.getRawPayload(), RAW_MAP);
        } catch (Exception exception) {
            throw new IllegalArgumentException("SKU「" + record.getSku() + "」的产品字段无法解析", exception);
        }
    }

    private String write(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成产品合并结果", exception);
        }
    }

    private String label(String field) {
        return FIELD_LABELS.getOrDefault(field, field);
    }

    private String display(Object value) {
        if (value == null) return "—";
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static String safeParent(String value) {
        return value == null || value.isBlank() ? "（无款号）" : value;
    }

    private String catalogFingerprint(List<ProductRecord> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.stream().sorted(Comparator.comparing(ProductRecord::getSku).thenComparing(row -> row.getId() == null ? 0L : row.getId()))
                    .map(this::stateLine)
                    .forEach(line -> digest.update((line + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String stateLine(ProductRecord row) {
        return String.join("|",
                String.valueOf(row.getId()), row.getSku(), String.valueOf(row.getSourceUpdatedAt()),
                String.valueOf(row.getRawPayload()), String.valueOf(row.getLastSyncedAt()),
                String.valueOf(row.getPrice()), String.valueOf(row.getWfsAvailable()),
                String.valueOf(row.getNonWfsAvailable()), String.valueOf(row.getReviewCount()),
                String.valueOf(row.getAverageRating()), String.valueOf(row.getListingStatus()));
    }

    public enum ChangeType { ADD, UPDATE, RETIRE, UNCHANGED }

    public record FieldChange(String field, String label, String beforeValue, String afterValue) { }

    public record PlannedChange(
            ChangeType type,
            String sku,
            String parentSku,
            String productName,
            String color,
            String size,
            boolean restored,
            boolean wholeStyle,
            List<FieldChange> fields
    ) { }

    public record Conflict(String sku, List<Integer> rowNumbers, List<String> fields) { }

    public record Plan(
            int sourceRows,
            int uniqueSkuCount,
            List<ProductRecord> resolvedRecords,
            List<PlannedChange> changes,
            List<Conflict> conflicts,
            String baselineFingerprint
    ) {
        public long count(ChangeType type) {
            return changes.stream().filter(change -> change.type() == type).count();
        }
    }
}
