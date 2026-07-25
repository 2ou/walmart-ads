package com.ai.walmartads.product.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductCatalogService {

    private static final List<String> APPAREL_SIZE_ORDER = List.of(
            "XXS", "XS", "S", "M", "L", "XL",
            "1X", "XXL", "2XL", "2X", "XXXL", "3XL", "3X",
            "4XL", "4X", "5XL", "5X", "6XL", "6X", "ONE SIZE", "OS"
    );
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductCatalogService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CatalogPayload load() {
        List<SourceProduct> rows = loadRows(null);
        Map<String, List<SourceProduct>> groups = rows.stream()
                .collect(Collectors.groupingBy(
                        SourceProduct::parentSku,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<ProductSummary> products = groups.entrySet().stream()
                .map(entry -> summary(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparing(ProductSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProductSummary::parentSku))
                .toList();

        long withMainImage = rows.stream().filter(row -> present(row.mainImageUrl())).count();
        long completeListings = rows.stream().filter(row -> listingCompleteness(row) == 100).count();
        int sourceFiles = (int) rows.stream().map(SourceProduct::sourceFileName).filter(ProductCatalogService::present).distinct().count();
        LocalDateTime latestUpdated = rows.stream()
                .map(SourceProduct::sourceUpdatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new CatalogPayload(
                new CatalogMeta(
                        groups.size(),
                        rows.size(),
                        withMainImage,
                        completeListings,
                        sourceFiles,
                        latestUpdated
                ),
                products
        );
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(String parentSku) {
        String safeParentSku = parentSku == null ? "" : parentSku.trim();
        if (safeParentSku.isEmpty()) {
            throw new IllegalArgumentException("父 SKU 不能为空。");
        }
        List<SourceProduct> rows = loadRows(safeParentSku);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("产品库中找不到父 SKU：" + safeParentSku);
        }
        SourceProduct representative = representative(rows);
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.add(row.swatchImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                })
                .toList());
        List<Variant> variants = rows.stream()
                .sorted(variantComparator())
                .map(ProductCatalogService::variant)
                .toList();
        List<ColorGroup> colorGroups = colorGroups(rows);
        return new ProductDetail(
                safeParentSku,
                representative.productName(),
                representative.brand(),
                representative.productType(),
                representative.shortDescription(),
                representative.keyFeatures(),
                representative.mainImageUrl(),
                images,
                listingCompleteness(representative),
                rows.size(),
                distinctCount(rows, SourceProduct::color),
                distinctCount(rows, SourceProduct::size),
                representative.sourceFileName(),
                representative.sourceUpdatedAt(),
                attributes(representative),
                representative.rawPayload(),
                colorGroups,
                variants
        );
    }

    private static List<ColorGroup> colorGroups(List<SourceProduct> rows) {
        Map<String, List<SourceProduct>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(colorKey(row.color()), ignored -> new ArrayList<>()).add(row));
        return grouped.values().stream()
                .map(ProductCatalogService::colorGroup)
                .sorted(Comparator
                        .comparing(ColorGroup::primaryColor).reversed()
                        .thenComparing(ColorGroup::color, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static ColorGroup colorGroup(List<SourceProduct> rows) {
        SourceProduct representative = representative(rows);
        List<Variant> variants = rows.stream()
                .sorted(colorVariantComparator())
                .map(ProductCatalogService::variant)
                .toList();
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                })
                .toList());
        List<String> sizes = variants.stream()
                .map(Variant::size)
                .filter(ProductCatalogService::present)
                .distinct()
                .sorted(apparelSizeComparator())
                .toList();
        return new ColorGroup(
                present(representative.color()) ? representative.color().trim() : "未标注颜色",
                representative.colorCategory(),
                representative.patternName(),
                representative.mainImageUrl(),
                representative.swatchImageUrl(),
                images,
                sizes,
                variants.size(),
                variants.stream().anyMatch(Variant::primaryVariant),
                variants
        );
    }

    private static Variant variant(SourceProduct row) {
        List<String> images = new ArrayList<>();
        images.add(row.mainImageUrl());
        images.addAll(row.secondaryImageUrls());
        return new Variant(
                row.sku(),
                row.productId(),
                row.productIdType(),
                row.color(),
                row.colorCategory(),
                row.size(),
                row.sizeGroup(),
                row.patternName(),
                row.materialName(),
                row.mainImageUrl(),
                row.swatchImageUrl(),
                distinctStrings(images),
                row.primaryVariant(),
                row.sourceUpdatedAt()
        );
    }

    private static Comparator<SourceProduct> variantComparator() {
        return Comparator
                .comparing(SourceProduct::primaryVariant).reversed()
                .thenComparing(SourceProduct::color, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(SourceProduct::size, Comparator.nullsLast(apparelSizeComparator()))
                .thenComparing(SourceProduct::sku);
    }

    private static Comparator<SourceProduct> colorVariantComparator() {
        return Comparator
                .comparing(SourceProduct::size, Comparator.nullsLast(apparelSizeComparator()))
                .thenComparing(SourceProduct::sku);
    }

    private static Comparator<String> apparelSizeComparator() {
        return Comparator
                .comparingInt(ProductCatalogService::apparelSizeRank)
                .thenComparing(String.CASE_INSENSITIVE_ORDER);
    }

    private static int apparelSizeRank(String value) {
        if (!present(value)) {
            return Integer.MAX_VALUE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        for (int index = 0; index < APPAREL_SIZE_ORDER.size(); index++) {
            if (APPAREL_SIZE_ORDER.get(index).replace(" ", "").equals(normalized)) {
                return index;
            }
        }
        try {
            return 100 + Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 1000;
        }
    }

    private static String colorKey(String value) {
        return present(value) ? value.trim().toLowerCase(Locale.ROOT) : "__unlabeled__";
    }

    private List<SourceProduct> loadRows(String parentSku) {
        String whereParent = parentSku == null ? "" : " and product.parent_sku = :parentSku ";
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (parentSku != null) {
            parameters.addValue("parentSku", parentSku);
        }
        return jdbc.queryForList("""
                select product.sku,
                       product.parent_sku,
                       product.product_type,
                       product.product_id,
                       product.product_id_type,
                       product.product_name,
                       product.brand,
                       product.short_description,
                       product.key_features,
                       product.main_image_url,
                       product.secondary_image_urls,
                       product.color,
                       product.color_category,
                       product.size,
                       product.size_group,
                       product.pattern_name,
                       product.material_name,
                       product.gender,
                       product.variant_group_id,
                       product.raw_payload,
                       product.source_updated_at,
                       file.file_name as source_file_name
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id
                   and file.active = true
                 where product.current_version = true
                """ + whereParent + """
                 order by product.parent_sku, product.sku
                """, parameters).stream().map(this::sourceProduct).toList();
    }

    private SourceProduct sourceProduct(Map<String, Object> row) {
        Map<String, Object> rawPayload = objectMap(row.get("raw_payload"));
        String parentSku = text(row, "parent_sku");
        if (!present(parentSku)) {
            parentSku = parentSku(text(row, "sku"));
        }
        return new SourceProduct(
                text(row, "sku"),
                parentSku,
                text(row, "product_type"),
                text(row, "product_id"),
                text(row, "product_id_type"),
                text(row, "product_name"),
                text(row, "brand"),
                text(row, "short_description"),
                stringList(row.get("key_features")),
                text(row, "main_image_url"),
                stringList(row.get("secondary_image_urls")),
                text(row, "color"),
                text(row, "color_category"),
                text(row, "size"),
                text(row, "size_group"),
                text(row, "pattern_name"),
                text(row, "material_name"),
                text(row, "gender"),
                text(row, "variant_group_id"),
                rawPayload,
                rawText(rawPayload, "swatchImageUrl"),
                truthy(rawPayload.get("isPrimaryVariant")),
                localDateTime(row.get("source_updated_at")),
                text(row, "source_file_name")
        );
    }

    private ProductSummary summary(String parentSku, List<SourceProduct> rows) {
        SourceProduct representative = representative(rows);
        LocalDateTime updatedAt = rows.stream()
                .map(SourceProduct::sourceUpdatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        int completeness = rows.stream().mapToInt(ProductCatalogService::listingCompleteness).max().orElse(0);
        List<String> images = distinctStrings(rows.stream()
                .flatMap(row -> {
                    List<String> rowImages = new ArrayList<>();
                    rowImages.add(row.mainImageUrl());
                    rowImages.addAll(row.secondaryImageUrls());
                    return rowImages.stream();
                }).toList());
        List<String> searchTerms = new ArrayList<>();
        rows.forEach(row -> {
            searchTerms.add(row.sku());
            searchTerms.add(row.parentSku());
            searchTerms.add(row.productId());
            searchTerms.add(row.productIdType());
            searchTerms.add(row.productName());
            searchTerms.add(row.brand());
            searchTerms.add(row.productType());
            searchTerms.add(row.color());
            searchTerms.add(row.colorCategory());
            searchTerms.add(row.size());
            searchTerms.add(row.sizeGroup());
            searchTerms.add(row.patternName());
            searchTerms.add(row.materialName());
            searchTerms.add(row.variantGroupId());
        });
        return new ProductSummary(
                parentSku,
                representative.productName(),
                representative.brand(),
                representative.productType(),
                representative.mainImageUrl(),
                rows.size(),
                distinctCount(rows, SourceProduct::color),
                distinctCount(rows, SourceProduct::size),
                images.size(),
                completeness,
                representative.sourceFileName(),
                updatedAt,
                distinctStrings(searchTerms)
        );
    }

    private static SourceProduct representative(List<SourceProduct> rows) {
        return rows.stream()
                .max(Comparator
                        .comparingInt(ProductCatalogService::representativeScore)
                        .thenComparing(SourceProduct::sourceUpdatedAt, Comparator.nullsFirst(LocalDateTime::compareTo)))
                .orElseThrow();
    }

    private static int representativeScore(SourceProduct row) {
        int score = row.primaryVariant() ? 100 : 0;
        score += present(row.productName()) ? 20 : 0;
        score += present(row.shortDescription()) ? 20 : 0;
        score += row.keyFeatures().isEmpty() ? 0 : 15;
        score += present(row.mainImageUrl()) ? 15 : 0;
        score += Math.min(10, row.secondaryImageUrls().size());
        return score;
    }

    private static int listingCompleteness(SourceProduct row) {
        int presentChecks = 0;
        presentChecks += present(row.productName()) ? 1 : 0;
        presentChecks += present(row.shortDescription()) ? 1 : 0;
        presentChecks += row.keyFeatures().isEmpty() ? 0 : 1;
        presentChecks += present(row.mainImageUrl()) ? 1 : 0;
        presentChecks += row.secondaryImageUrls().isEmpty() ? 0 : 1;
        return presentChecks * 20;
    }

    private static Map<String, String> attributes(SourceProduct row) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "商品类型", row.productType());
        put(attributes, "品牌", row.brand());
        put(attributes, "适用人群", row.gender());
        put(attributes, "材质", row.materialName());
        put(attributes, "图案", row.patternName());
        put(attributes, "尺码组", row.sizeGroup());
        put(attributes, "年龄段", rawText(row.rawPayload(), "ageGroup"));
        put(attributes, "版型", rawText(row.rawPayload(), "clothingFit"));
        put(attributes, "服装风格", rawText(row.rawPayload(), "clothingStyle"));
        put(attributes, "适用场景", rawText(row.rawPayload(), "clothing_occasion"));
        put(attributes, "适用活动", rawText(row.rawPayload(), "activity"));
        put(attributes, "面料结构", rawText(row.rawPayload(), "fabric_construction"));
        put(attributes, "洗护说明", rawText(row.rawPayload(), "fabricCareInstructions"));
        put(attributes, "履约方式", rawText(row.rawPayload(), "thirdPartyProductFulfillmentType"));
        return attributes;
    }

    private static void put(Map<String, String> target, String label, String value) {
        if (present(value)) {
            target.put(label, value.trim());
        }
    }

    private static int distinctCount(List<SourceProduct> rows, Function<SourceProduct, String> getter) {
        return (int) rows.stream()
                .map(getter)
                .filter(ProductCatalogService::present)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .count();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            if (value instanceof String text) {
                if (text.isBlank()) {
                    return List.of();
                }
                if (!text.trim().startsWith("[")) {
                    return List.of(text.trim());
                }
                return objectMapper.readValue(text, STRING_LIST).stream()
                        .filter(ProductCatalogService::present)
                        .map(String::trim)
                        .toList();
            }
            return objectMapper.convertValue(value, STRING_LIST).stream()
                    .filter(ProductCatalogService::present)
                    .map(String::trim)
                    .toList();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return List.of(String.valueOf(value));
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            if (value instanceof String text) {
                return objectMapper.readValue(text, OBJECT_MAP);
            }
            return objectMapper.convertValue(value, OBJECT_MAP);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private static List<String> distinctStrings(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.stream().filter(ProductCatalogService::present).map(String::trim).forEach(result::add);
        return List.copyOf(result);
    }

    private static String rawText(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(ProductCatalogService::present).collect(Collectors.joining(" / "));
        }
        return String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return List.of("true", "yes", "y", "1").contains(normalized);
    }

    private static String parentSku(String sku) {
        if (!present(sku)) {
            return "";
        }
        int separator = sku.indexOf('-');
        return separator > 0 ? sku.substring(0, separator) : sku;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record CatalogPayload(CatalogMeta meta, List<ProductSummary> products) {
    }

    public record CatalogMeta(
            int parentProductCount,
            int variantCount,
            long variantsWithMainImage,
            long completeListingVariants,
            int sourceFileCount,
            LocalDateTime latestUpdatedAt
    ) {
    }

    public record ProductSummary(
            String parentSku,
            String title,
            String brand,
            String productType,
            String imageUrl,
            int variantCount,
            int colorCount,
            int sizeCount,
            int imageCount,
            int listingCompleteness,
            String sourceFileName,
            LocalDateTime updatedAt,
            List<String> searchTerms
    ) {
    }

    public record ProductDetail(
            String parentSku,
            String title,
            String brand,
            String productType,
            String shortDescription,
            List<String> keyFeatures,
            String mainImageUrl,
            List<String> images,
            int listingCompleteness,
            int variantCount,
            int colorCount,
            int sizeCount,
            String sourceFileName,
            LocalDateTime updatedAt,
            Map<String, String> attributes,
            Map<String, Object> rawFields,
            List<ColorGroup> colorGroups,
            List<Variant> variants
    ) {
    }

    public record ColorGroup(
            String color,
            String colorCategory,
            String pattern,
            String mainImageUrl,
            String swatchImageUrl,
            List<String> images,
            List<String> sizes,
            int variantCount,
            boolean primaryColor,
            List<Variant> variants
    ) {
    }

    public record Variant(
            String sku,
            String productId,
            String productIdType,
            String color,
            String colorCategory,
            String size,
            String sizeGroup,
            String pattern,
            String material,
            String imageUrl,
            String swatchImageUrl,
            List<String> images,
            boolean primaryVariant,
            LocalDateTime updatedAt
    ) {
    }

    private record SourceProduct(
            String sku,
            String parentSku,
            String productType,
            String productId,
            String productIdType,
            String productName,
            String brand,
            String shortDescription,
            List<String> keyFeatures,
            String mainImageUrl,
            List<String> secondaryImageUrls,
            String color,
            String colorCategory,
            String size,
            String sizeGroup,
            String patternName,
            String materialName,
            String gender,
            String variantGroupId,
            Map<String, Object> rawPayload,
            String swatchImageUrl,
            boolean primaryVariant,
            LocalDateTime sourceUpdatedAt,
            String sourceFileName
    ) {
    }

}
