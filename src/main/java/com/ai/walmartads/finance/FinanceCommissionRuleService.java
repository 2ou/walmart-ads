package com.ai.walmartads.finance;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** User-confirmed promotion terms. The standard tier applies when no rule matches. */
@Service
public class FinanceCommissionRuleService {
    private static final BigDecimal MAXIMUM_RATE = new BigDecimal("0.15");
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("9999999999.99");
    private final JdbcTemplate jdbc;

    public FinanceCommissionRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Rule(long id, String itemId, LocalDate startDate, LocalDate endDate,
                       BigDecimal maximumPriceUsd, BigDecimal rate) {
    }

    public record RuleInput(String itemId, LocalDate startDate, LocalDate endDate,
                            BigDecimal maximumPriceUsd, BigDecimal rate) {
    }

    @Transactional(readOnly = true)
    public List<Rule> list() {
        return list(WorkspaceContext.requireId());
    }

    private List<Rule> list(long workspaceId) {
        return jdbc.query("""
                select id, item_id, start_date, end_date, maximum_price_usd, rate
                  from wm_ads_finance_commission_rule where workspace_id = ?
                 order by start_date desc, item_id, end_date
                """, (rs, i) -> new Rule(rs.getLong("id"), rs.getString("item_id"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("maximum_price_usd"), rs.getBigDecimal("rate")), workspaceId);
    }

    public Map<String, List<Rule>> loadApprovedRules() {
        // The estimate service is also used by offline callers without a workspace principal.
        return WorkspaceContext.current()
                .map(p -> list(p.workspaceId()).stream().collect(Collectors.groupingBy(Rule::itemId)))
                .orElseGet(Map::of);
    }

    public static Optional<BigDecimal> matchRate(Map<String, List<Rule>> rules, String itemId,
                                                  LocalDate orderDate, BigDecimal unitPrice) {
        if (itemId == null || orderDate == null || unitPrice == null || unitPrice.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal matched = null;
        for (Rule rule : rules.getOrDefault(itemId, List.of())) {
            if (orderDate.isBefore(rule.startDate()) || orderDate.isAfter(rule.endDate())
                    || unitPrice.compareTo(rule.maximumPriceUsd()) > 0) {
                continue;
            }
            if (matched != null && matched.compareTo(rule.rate()) != 0) {
                return Optional.empty();
            }
            matched = rule.rate();
        }
        return Optional.ofNullable(matched);
    }

    @Transactional
    public List<Rule> save(List<RuleInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 2000) {
            throw new IllegalArgumentException("请一次提交 1–2000 条已确认的减佣规则");
        }
        long workspaceId = WorkspaceContext.requireId();
        lockWorkspace(workspaceId);
        Map<RuleDates, RuleInput> updates = new LinkedHashMap<>();
        for (RuleInput input : inputs) {
            RuleInput normalized = validate(input);
            RuleInput previous = updates.putIfAbsent(RuleDates.of(normalized), normalized);
            if (previous != null && (previous.rate().compareTo(normalized.rate()) != 0
                    || previous.maximumPriceUsd().compareTo(normalized.maximumPriceUsd()) != 0)) {
                throw new IllegalArgumentException("同一 Item ID 和有效期存在互相冲突的规则：" + normalized.itemId());
            }
        }
        validateCatalogItems(workspaceId, updates.values().stream().map(RuleInput::itemId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

        Map<String, List<RuleInput>> merged = new LinkedHashMap<>();
        for (Rule rule : list(workspaceId)) {
            RuleInput original = new RuleInput(rule.itemId(), rule.startDate(), rule.endDate(),
                    rule.maximumPriceUsd(), rule.rate());
            if (!updates.containsKey(RuleDates.of(original))) {
                merged.computeIfAbsent(rule.itemId(), ignored -> new ArrayList<>()).add(original);
            }
        }
        for (RuleInput update : updates.values()) {
            List<RuleInput> sameItem = merged.computeIfAbsent(update.itemId(), ignored -> new ArrayList<>());
            for (RuleInput existing : sameItem) {
                if (!update.startDate().isAfter(existing.endDate()) && !update.endDate().isBefore(existing.startDate())
                        && update.rate().compareTo(existing.rate()) != 0) {
                    throw new IllegalArgumentException("同一 Item ID 的重叠有效期只能使用同一费率：" + update.itemId());
                }
            }
            sameItem.add(update);
        }

        jdbc.batchUpdate("""
                insert into wm_ads_finance_commission_rule
                    (workspace_id, item_id, start_date, end_date, maximum_price_usd, rate)
                values (?, ?, ?, ?, ?, ?)
                on duplicate key update maximum_price_usd = values(maximum_price_usd), rate = values(rate)
                """, updates.values().stream().map(r -> new Object[]{workspaceId, r.itemId(),
                Date.valueOf(r.startDate()), Date.valueOf(r.endDate()), r.maximumPriceUsd(), r.rate()}).toList());
        return list(workspaceId);
    }

    @Transactional
    public List<Rule> remove(long id) {
        long workspaceId = WorkspaceContext.requireId();
        lockWorkspace(workspaceId);
        jdbc.update("delete from wm_ads_finance_commission_rule where workspace_id = ? and id = ?", workspaceId, id);
        return list(workspaceId);
    }

    private void lockWorkspace(long workspaceId) {
        jdbc.queryForObject("select id from wm_ads_workspace where id = ? for update", Long.class, workspaceId);
    }

    private void validateCatalogItems(long workspaceId, Set<String> itemIds) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        List<String> knownItems = named.queryForList("""
                select distinct product_id from wm_ads_product_record
                 where workspace_id = :workspaceId and current_version = true and product_id in (:itemIds)
                """, Map.of("workspaceId", workspaceId, "itemIds", itemIds), String.class);
        itemIds.removeAll(knownItems);
        if (!itemIds.isEmpty()) {
            throw new IllegalArgumentException("当前工作台产品库找不到 Item ID：" + String.join("、", itemIds));
        }
    }

    private static RuleInput validate(RuleInput input) {
        if (input == null || input.itemId() == null || !input.itemId().trim().matches("[0-9]{1,24}")) {
            throw new IllegalArgumentException("减佣规则需要有效的数字 Item ID");
        }
        if (input.startDate() == null || input.endDate() == null || input.endDate().isBefore(input.startDate())
                || input.startDate().getYear() < 2000 || input.endDate().getYear() > 9999) {
            throw new IllegalArgumentException("减佣规则需要有效的起止日期");
        }
        BigDecimal price = input.maximumPriceUsd();
        BigDecimal rate = input.rate();
        if (price == null || price.signum() <= 0 || price.compareTo(MAXIMUM_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("减佣价格上限必须为正数，最多两位小数");
        }
        if (rate == null || rate.signum() < 0 || rate.compareTo(MAXIMUM_RATE) > 0
                || rate.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("减佣费率应在 0%–15% 之间，例如 2% 传 0.02");
        }
        return new RuleInput(input.itemId().trim(), input.startDate(), input.endDate(), price, rate);
    }

    private record RuleDates(String itemId, LocalDate startDate, LocalDate endDate) {
        static RuleDates of(RuleInput input) {
            return new RuleDates(input.itemId(), input.startDate(), input.endDate());
        }
    }
}
