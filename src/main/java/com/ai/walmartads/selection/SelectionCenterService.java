package com.ai.walmartads.selection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.ai.walmartads.selection.SelectionCenterRules.RULE_VERSION;
import static com.ai.walmartads.selection.SelectionCenterStore.*;

@Service
public class SelectionCenterService {
    private final SelectionCenterStore store;
    private final ObjectMapper mapper;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    SelectionCenterService(SelectionCenterStore store, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.store = store;
        this.mapper = mapper;
        write = new TransactionTemplate(transactions);
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
    }

    public record ImportResult(long runId, LocalDate date, int candidates, boolean complete, boolean inventoryComplete,
                               boolean duplicate, boolean published) {}
    public record ItemRow(String itemId, long styleId, String title, String brand, String category, String imageUrl,
                          BigDecimal price, Integer rank, Integer categoryRank, Integer previousRank, Integer previousCategoryRank, Long sales,
                          Long sevenDaySales, int stockIncreaseSkus, long stockIncreaseUnits, int comparableSkus,
                          int expectedSkus, boolean watched, boolean groupVerified, LocalDate firstSeen,
                          Long currentStockTotal, int currentStockKnownSkus, int currentStockExpectedSkus) {}
    public record VariantRow(String itemId, String color, String size, Integer previousQuantity, Integer currentQuantity,
                             Long sales, Long stockIncrease, String availability, String sellerId, String fulfillment,
                             boolean comparable, String issue) {}
    public record Detail(ItemRow item, List<Point> history, List<VariantRow> variants) {}

    public ImportResult ingest(SelectionSnapshot input) {
        if (input == null) throw new IllegalArgumentException("缺少采集批次");
        SelectionCenterRules.validate(input);
        List<SelectionCenterRules.Group> groups = SelectionCenterRules.groups(input);
        String payloadHash = sha256(toJson(input));
        return Objects.requireNonNull(write.execute(status -> persist(input, groups, payloadHash)));
    }

    private ImportResult persist(SelectionSnapshot input, List<SelectionCenterRules.Group> groups, String payloadHash) {
        String sourceHash = sha256((input.sourceUrl() + "\n" + input.zip()).getBytes(StandardCharsets.UTF_8));
        Monitor monitor = store.lockMonitor(sourceHash, input.sourceUrl(), input.zip());
        if (monitor == null) throw new IllegalStateException("无法初始化新品监测来源");
        Batch existing = store.findBatch(monitor.id(), input.date(), payloadHash);
        if (existing != null) return importResult(monitor.id(), existing, groups.size(), true);

        Instant observed = input.observedAt() == null ? Instant.now() : input.observedAt();
        Batch batch = store.insertBatch(monitor.id(), input, observed, payloadHash);
        for (SelectionCenterRules.Group group : groups) {
            Set<String> memberIds = new HashSet<>();
            memberIds.add(group.item().itemId());
            memberIds.add(group.representative());
            group.variants().forEach(variant -> memberIds.add(variant.itemId()));
            long style = store.resolveStyle(monitor.id(), group.key(), group.verified(), input.date(), List.copyOf(memberIds));
            store.insertStyle(batch.id(), style, group.representative(), group.item(), group.expected(), group.verified());
            store.insertVariants(batch.id(), group.variants().stream().map(v -> new Variant(style, v)).toList());
        }

        Day selected = store.day(monitor.id(), input.date());
        if (selected == null || prefer(batch, store.batch(selected.runId()))) {
            store.setDay(monitor.id(), input.date(), batch.id(), null, false, RULE_VERSION);
            // D changes the D/D-1 and D+1/D comparisons. Later days may have an affected 7-day tie breaker.
            for (int offset = 0; offset <= 7; offset++) {
                LocalDate date = input.date().plusDays(offset);
                Day target = store.day(monitor.id(), date);
                if (target != null) calculateDay(monitor.id(), target);
            }
        }
        return importResult(monitor.id(), batch, groups.size(), false);
    }

    private ImportResult importResult(long monitorId, Batch batch, int count, boolean duplicate) {
        Day chosen = store.day(monitorId, batch.date());
        boolean published = chosen != null && chosen.runId() == batch.id() && chosen.published();
        return new ImportResult(batch.id(), batch.date(), count, batch.complete(), batch.inventoryComplete(), duplicate, published);
    }

    private boolean prefer(Batch incoming, Batch chosen) {
        return Comparator.comparing(Batch::inventoryComplete).thenComparing(Batch::complete)
                .thenComparingInt(Batch::completedPages).thenComparing(Batch::observedAt)
                .thenComparingLong(Batch::id).compare(incoming, chosen) > 0;
    }

    private void calculateDay(long monitorId, Day current) {
        Day previous = store.day(monitorId, current.date().minusDays(1));
        // rule v2-partial: 解除批次级 all-or-nothing 门槛。只要存在相邻日期的批次，
        // 就用两日都可比的 SKU 差分出榜；完全采不齐的批次因 comparable=0 自然不会 qualify，
        // 不会误出空榜。款级 80% 可比门槛（下方 qualifies）保留，防止用极少 SKU 出榜。
        if (previous == null) {
            store.setDay(monitorId, current.date(), current.runId(), null, false, RULE_VERSION);
            store.replaceResults(monitorId, current.date(), List.of());
            return;
        }
        List<Style> styles = store.styles(current.runId());
        Map<Long, Style> oldStyles = index(store.styles(previous.runId()), Style::id);
        List<Variant> oldVariants = store.variants(previous.runId());
        Map<String, Variant> oldByItem = index(oldVariants, row -> row.value().itemId());
        Map<Long, List<Variant>> oldByStyle = oldVariants.stream().collect(Collectors.groupingBy(Variant::styleId));
        Map<Long, List<Variant>> newByStyle = store.variants(current.runId()).stream().collect(Collectors.groupingBy(Variant::styleId));
        Map<Long, PriorSales> priorSales = index(store.priorSixDaySales(monitorId, current.date()), PriorSales::styleId);
        List<DailyResult> unranked = new ArrayList<>();
        for (Style style : styles) {
            List<Variant> variants = newByStyle.getOrDefault(style.id(), List.of());
            Set<String> union = new HashSet<>();
            for (Variant variant : oldByStyle.getOrDefault(style.id(), List.of())) union.add(variant.value().itemId());
            long units = 0;
            long increased = 0;
            int increasedCount = 0;
            int comparable = 0;
            for (Variant member : variants) {
                SelectionSnapshot.Variant v = member.value();
                union.add(v.itemId());
                Variant previousMember = oldByItem.get(v.itemId());
                SelectionCenterRules.Delta delta = SelectionCenterRules.compare(previousMember == null ? null : previousMember.value(), v);
                if (delta.comparable()) {
                    comparable++;
                    units += delta.sales();
                    if (delta.stockIncrease() > 0) {
                        increasedCount++;
                        increased += delta.stockIncrease();
                    }
                }
            }
            Style old = oldStyles.get(style.id());
            int expected = Math.max(union.size(), Math.max(style.expectedSkus(), old == null ? 0 : old.expectedSkus()));
            boolean qualifies = style.groupVerified() && comparable > 0 && 100L * comparable >= 80L * expected;
            PriorSales prior = priorSales.get(style.id());
            Long sevenDay = prior == null ? null : prior.total();
            if (qualifies) sevenDay = units + (prior == null ? 0L : prior.total());
            unranked.add(new DailyResult(style.id(), qualifies ? units : null, sevenDay, increasedCount, increased,
                    comparable, expected, null, null));
        }
        Map<Long, Style> byStyle = index(styles, Style::id);
        Comparator<DailyResult> highSalesFirst = Comparator.comparing(DailyResult::sales, Comparator.reverseOrder())
                .thenComparing(DailyResult::sevenDaySales, Comparator.reverseOrder()).thenComparingLong(DailyResult::styleId);
        List<DailyResult> sorted = unranked.stream().filter(r -> r.sales() != null).sorted(highSalesFirst).toList();
        Map<String, Integer> categoryCounts = new HashMap<>();
        List<DailyResult> ranked = new ArrayList<>();
        for (DailyResult result : sorted) {
            String category = Objects.toString(byStyle.get(result.styleId()).category(), "").strip().toLowerCase(Locale.ROOT);
            Integer categoryRank = category.isEmpty() ? null : categoryCounts.merge(category, 1, Integer::sum);
            ranked.add(new DailyResult(result.styleId(), result.sales(), result.sevenDaySales(), result.stockIncreaseSkus(),
                    result.stockIncreaseUnits(), result.comparableSkus(), result.expectedSkus(), ranked.size() + 1, categoryRank));
        }
        boolean published = !ranked.isEmpty();
        unranked.stream().filter(r -> r.sales() == null).forEach(ranked::add);
        store.setDay(monitorId, current.date(), current.runId(), previous.runId(), published, RULE_VERSION);
        store.replaceResults(monitorId, current.date(), ranked);
    }

    public Map<String, Object> board(LocalDate date, String account) {
        return read.execute(status -> readBoard(date, account));
    }

    private Map<String, Object> readBoard(LocalDate requested, String account) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "EMPTY");
        output.put("availableDates", List.of());
        output.put("items", List.of());
        Monitor monitor = store.latestMonitor();
        if (monitor == null) return output;
        Batch latest = store.latestBatch(monitor.id());
        if (latest == null) return output;
        output.put("zip", monitor.zip());
        output.put("sourceUrl", monitor.sourceUrl());
        output.put("availableDates", store.dates(monitor.id()));
        output.put("latestDate", latest.date());
        output.put("latestExpectedPages", latest.expectedPages());
        output.put("latestCompletedPages", latest.completedPages());
        output.put("latestComplete", latest.complete());
        output.put("latestInventoryComplete", latest.inventoryComplete());
        output.put("latestIssue", latest.issue());
        output.put("latestObservedAt", latest.observedAt());
        Day day = chooseDay(monitor.id(), latest, requested);
        if (day == null) return output;
        Batch selected = store.batch(day.runId());
        List<ItemRow> rows = rows(monitor.id(), day, account, null);
        output.put("status", day.published() ? "RANKED" : !selected.complete() ? "PARTIAL" : "BASELINE");
        output.put("date", day.date());
        output.put("observedAt", selected.observedAt());
        output.put("complete", selected.complete());
        output.put("inventoryComplete", selected.inventoryComplete());
        output.put("expectedPages", selected.expectedPages());
        output.put("completedPages", selected.completedPages());
        output.put("declaredCount", selected.declaredCount());
        output.put("issue", selected.issue());
        output.put("candidates", rows.size());
        output.put("comparableStyles", rows.stream().filter(row -> row.sales() != null).count());
        output.put("items", rows);
        return output;
    }

    private Day chooseDay(long monitorId, Batch latest, LocalDate date) {
        if (date != null) return store.day(monitorId, date);
        Day ranked = store.latestPublished(monitorId);
        return ranked == null ? store.day(monitorId, latest.date()) : ranked;
    }

    private List<ItemRow> rows(long monitorId, Day day, String account, String itemId) {
        List<Style> styles = store.styles(day.runId());
        Long styleId = null;
        if (itemId != null) {
            styles = styles.stream().filter(style -> style.itemId().equals(itemId)).toList();
            if (styles.isEmpty()) return List.of();
            styleId = styles.getFirst().id();
        }
        Map<Long, StockQuantities> stockByStyle = store.stockQuantities(day.runId(), styleId);
        Map<Long, DailyResult> results = index(store.results(monitorId, day.date()), DailyResult::styleId);
        Map<Long, DailyResult> previous = index(store.results(monitorId, day.date().minusDays(1)), DailyResult::styleId);
        Map<Long, Style> oldStyles = day.previousRunId() == null ? Map.of()
                : index(store.styles(day.previousRunId()), Style::id);
        Set<Long> watch = Set.copyOf(store.watched(account));
        return styles.stream().map(style -> {
            DailyResult result = results.get(style.id());
            DailyResult prior = previous.get(style.id());
            Style old = oldStyles.get(style.id());
            Integer previousCategoryRank = prior == null || old == null || !Objects.equals(style.category(), old.category())
                    ? null : prior.categoryRank();
            StockQuantities stock = stockByStyle.getOrDefault(style.id(), StockQuantities.EMPTY);
            boolean completeStock = style.groupVerified() && style.expectedSkus() > 0 && stock.knownSkus() == style.expectedSkus();
            return new ItemRow(style.itemId(), style.id(), style.title(), style.brand(), style.category(), style.imageUrl(),
                    style.price(), result == null ? null : result.rank(), result == null ? null : result.categoryRank(),
                    prior == null ? null : prior.rank(), previousCategoryRank, result == null ? null : result.sales(),
                    result == null ? null : result.sevenDaySales(),
                    result == null ? 0 : result.stockIncreaseSkus(), result == null ? 0L : result.stockIncreaseUnits(),
                    result == null ? 0 : result.comparableSkus(), result == null ? style.expectedSkus() : result.expectedSkus(),
                    watch.contains(style.id()), style.groupVerified(), style.firstSeen(),
                    completeStock ? stock.total() : null, stock.knownSkus(), style.expectedSkus());
        }).sorted(Comparator.comparing(ItemRow::rank, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    public Detail detail(String itemId, LocalDate date, String account) {
        SelectionCenterRules.itemId(itemId);
        return read.execute(status -> readDetail(itemId, date, account));
    }

    private Detail readDetail(String itemId, LocalDate date, String account) {
        Monitor monitor = store.latestMonitor();
        if (monitor == null) return null;
        Batch latest = store.latestBatch(monitor.id());
        if (latest == null) return null;
        Day day = chooseDay(monitor.id(), latest, date);
        if (day == null) return null;
        ItemRow item = rows(monitor.id(), day, account, itemId).stream().findFirst().orElse(null);
        if (item == null) return null;
        Map<String, Variant> before = day.previousRunId() == null ? Map.of()
                : index(store.variants(day.previousRunId(), item.styleId()), v -> v.value().itemId());
        List<VariantRow> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Variant variant : store.variants(day.runId(), item.styleId())) {
            SelectionSnapshot.Variant v = variant.value();
            Variant old = before.get(v.itemId());
            variants.add(variantRow(old == null ? null : old.value(), v));
            seen.add(v.itemId());
        }
        for (Variant old : before.values()) {
            if (old.styleId() == item.styleId() && !seen.contains(old.value().itemId())) variants.add(variantRow(old.value(), null));
        }
        variants.sort(Comparator.comparing(VariantRow::color, Comparator.nullsLast(String::compareTo))
                .thenComparing(VariantRow::size, Comparator.nullsLast(String::compareTo)).thenComparing(VariantRow::itemId));
        return new Detail(item, store.history(monitor.id(), item.styleId(), day.date()), variants);
    }

    private VariantRow variantRow(SelectionSnapshot.Variant previous, SelectionSnapshot.Variant current) {
        SelectionCenterRules.Delta delta = SelectionCenterRules.compare(previous, current);
        SelectionSnapshot.Variant v = current == null ? previous : current;
        return new VariantRow(v.itemId(), v.color(), v.size(), delta.previousQuantity(), delta.currentQuantity(),
                delta.sales(), delta.stockIncrease(), current == null ? null : current.availability(), v.sellerId(),
                v.fulfillment(), delta.comparable(), delta.reason());
    }

    public boolean watch(String account, String itemId, boolean watched) {
        SelectionCenterRules.itemId(itemId);
        return Boolean.TRUE.equals(write.execute(status -> {
            Monitor monitor = store.latestMonitor();
            Long style = monitor == null ? null : store.resolveWatchStyle(monitor.id(), itemId);
            if (style == null) return false;
            store.setWatch(account, style, watched);
            return true;
        }));
    }

    private static <K, V> Map<K, V> index(List<V> rows, Function<V, K> keys) {
        return rows.stream().collect(Collectors.toMap(keys, Function.identity()));
    }

    private byte[] toJson(SelectionSnapshot input) {
        try {
            return mapper.writeValueAsBytes(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法读取采集快照", exception);
        }
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
