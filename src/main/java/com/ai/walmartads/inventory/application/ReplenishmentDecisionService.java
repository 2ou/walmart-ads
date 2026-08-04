package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.ReplenishmentDecision;
import com.ai.walmartads.inventory.repository.ReplenishmentDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ReplenishmentDecisionService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "CONFIRMED", "HOLD");
    private final ReplenishmentDecisionRepository repository;

    public ReplenishmentDecisionService(ReplenishmentDecisionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DecisionView> list() {
        return repository.findAll().stream()
                .map(ReplenishmentDecisionService::view)
                .sorted(Comparator.comparing(DecisionView::updatedAt).reversed())
                .toList();
    }

    @Transactional
    public DecisionView update(String parentSku, DecisionPatch patch) {
        String safeParentSku = requireText(parentSku, "款号", 180);
        Double finalQuantity = nonNegative(patch.finalQuantity(), "最终补货数量");
        if (finalQuantity != null && finalQuantity % 1 != 0) {
            throw new IllegalArgumentException("最终补货数量必须是整数。");
        }
        Double suggested = nonNegative(patch.systemSuggestedQuantity(), "系统建议数量");
        Double plan = nonNegative(patch.demandPlanQuantity(), "表单计划数量");
        Integer targetDays = patch.targetDays();
        if (targetDays != null && (targetDays < 7 || targetDays > 180)) {
            throw new IllegalArgumentException("目标覆盖天数必须在 7 到 180 天之间。");
        }
        String status = patch.status() == null || patch.status().isBlank()
                ? "DRAFT" : patch.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的决策状态：" + status);
        }
        if ("CONFIRMED".equals(status) && finalQuantity == null) {
            throw new IllegalArgumentException("确认决策前请填写最终补货数量。");
        }
        String note = optionalText(patch.note(), 500);
        ReplenishmentDecision decision = repository.findById(safeParentSku)
                .orElseGet(() -> new ReplenishmentDecision(safeParentSku));
        decision.update(
                decimal(finalQuantity),
                decimal(suggested),
                decimal(plan),
                targetDays,
                status,
                note
        );
        return view(repository.save(decision));
    }

    private static DecisionView view(ReplenishmentDecision value) {
        return new DecisionView(
                value.getParentSku(),
                number(value.getFinalQuantity()),
                number(value.getSystemSuggestedQuantity()),
                number(value.getDemandPlanQuantity()),
                value.getTargetDays(),
                value.getStatus(),
                value.getNote(),
                value.getUpdatedAt()
        );
    }

    private static Double nonNegative(Double value, String label) {
        if (value == null) return null;
        if (!Double.isFinite(value) || value < 0 || value > 1_000_000) {
            throw new IllegalArgumentException(label + "必须是 0 到 1000000 之间的数字。");
        }
        return value;
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空。");
        String safe = value.trim();
        if (safe.length() > maxLength) throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符。");
        return safe;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String safe = value.trim();
        if (safe.length() > maxLength) throw new IllegalArgumentException("决策备注不能超过 " + maxLength + " 个字符。");
        return safe;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    public record DecisionPatch(
            Double finalQuantity,
            Double systemSuggestedQuantity,
            Double demandPlanQuantity,
            Integer targetDays,
            String status,
            String note
    ) {
    }

    public record DecisionView(
            String parentSku,
            Double finalQuantity,
            Double systemSuggestedQuantity,
            Double demandPlanQuantity,
            Integer targetDays,
            String status,
            String note,
            LocalDateTime updatedAt
    ) {
    }
}
