package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.ParentDailySalesOverride;
import com.ai.walmartads.inventory.repository.ParentDailySalesOverrideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按款号（parentSku）维护的「日均销量(手动)」覆盖：读（parentSku -> 值）、按款号 upsert。
 * 与 SKU 级因子表完全解耦——维度是款号，不是 SKU。
 */
@Service
public class ParentDailySalesOverrideService {

    private final ParentDailySalesOverrideRepository repository;

    public ParentDailySalesOverrideService(ParentDailySalesOverrideRepository repository) {
        this.repository = repository;
    }

    /** parentSku -> manualDailySales（null 表示未覆盖，回退派生值） */
    public Map<String, Double> list() {
        Map<String, Double> result = new LinkedHashMap<>();
        repository.findAll().forEach(o -> {
            if (o.getParentSku() != null) {
                result.put(o.getParentSku(), o.getManualDailySales() == null ? null : o.getManualDailySales().doubleValue());
            }
        });
        return result;
    }

    @Transactional
    public Map<String, Double> update(String parentSku, ForecastFactorService.FactorPatch patch) {
        if (patch.field() == null || !"manualDailySales".equals(patch.field())) {
            throw new IllegalArgumentException("不支持的字段：" + patch.field());
        }
        Double value = patch.value();
        if (value != null && value < 0) {
            throw new IllegalArgumentException("日均销量(手动)不能为负");
        }
        if (value == null) {
            // 清除：直接删除该款号行，而非保留 null 值
            repository.findById(parentSku).ifPresent(repository::delete);
            return list();
        }
        ParentDailySalesOverride entity = repository.findById(parentSku).orElseGet(() -> new ParentDailySalesOverride(parentSku));
        entity.setManualDailySales(BigDecimal.valueOf(value));
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        return list();
    }
}
