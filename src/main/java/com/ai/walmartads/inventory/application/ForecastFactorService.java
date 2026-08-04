package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.DemandForecastRecord;
import com.ai.walmartads.importing.repository.DemandForecastRepository;
import com.ai.walmartads.inventory.domain.ForecastFactor;
import com.ai.walmartads.inventory.repository.ForecastFactorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 补货预测因子的读写：读（含需求预测表单的「需求计划」作为对照）、按 SKU 创建或更新（upsert）。
 * 因子表与需求预测导入解耦，这里只负责把两者在查询时拼装，不做版本化。
 */
@Service
public class ForecastFactorService {

    private final ForecastFactorRepository factorRepository;
    private final DemandForecastRepository demandForecastRepository;

    public ForecastFactorService(
            ForecastFactorRepository factorRepository,
            DemandForecastRepository demandForecastRepository
    ) {
        this.factorRepository = factorRepository;
        this.demandForecastRepository = demandForecastRepository;
    }

    public List<ForecastFactorDto> list() {
        Map<String, BigDecimal> planBySku = new LinkedHashMap<>();
        demandForecastRepository.findCurrentVersions().forEach(r -> {
            if (r.getSku() != null) planBySku.put(r.getSku(), r.getDemandPlan());
        });
        Map<String, ForecastFactor> factorBySku = new LinkedHashMap<>();
        factorRepository.findAll().forEach(f -> {
            if (f.getSku() != null) factorBySku.put(f.getSku(), f);
        });

        LinkedHashSet<String> allSkus = new LinkedHashSet<>();
        allSkus.addAll(planBySku.keySet());
        allSkus.addAll(factorBySku.keySet());

        List<ForecastFactorDto> result = new ArrayList<>();
        for (String sku : allSkus) {
            ForecastFactor f = factorBySku.get(sku);
            result.add(new ForecastFactorDto(
                    sku,
                    toDouble(f == null ? null : f.getSeasonalCoef()),
                    toDouble(f == null ? null : f.getGainDays()),
                    toDouble(f == null ? null : f.getSafetyDays()),
                    toDouble(f == null ? null : f.getReturnRate()),
                    toDouble(f == null ? null : f.getReturnReuseRate()),
                    toDouble(planBySku.get(sku))
            ));
        }
        return result;
    }

    private static final java.util.Set<String> ALLOWED_FIELDS = java.util.Set.of(
            "seasonalCoef", "gainDays", "safetyDays", "returnRate", "returnReuseRate");

    @Transactional
    public ForecastFactorDto update(String sku, FactorPatch patch) {
        if (patch.field() == null || !ALLOWED_FIELDS.contains(patch.field())) {
            throw new IllegalArgumentException("不支持的因子字段：" + patch.field());
        }
        ForecastFactor factor = factorRepository.findById(sku).orElseGet(() -> new ForecastFactor(sku));
        Double value = patch.value();
        switch (patch.field()) {
            case "seasonalCoef" -> factor.setSeasonalCoef(value == null ? null : BigDecimal.valueOf(value));
            case "gainDays" -> factor.setGainDays(value == null ? null : BigDecimal.valueOf(value));
            case "safetyDays" -> factor.setSafetyDays(value == null ? null : BigDecimal.valueOf(value));
            case "returnRate" -> factor.setReturnRate(value == null ? null : BigDecimal.valueOf(value));
            case "returnReuseRate" -> factor.setReturnReuseRate(value == null ? null : BigDecimal.valueOf(value));
            default -> throw new IllegalArgumentException("不支持的因子字段：" + patch.field());
        }
        factor.setUpdatedAt(LocalDateTime.now());
        ForecastFactor saved = factorRepository.save(factor);

        BigDecimal plan = demandForecastRepository.findCurrentVersions().stream()
                .filter(r -> sku.equals(r.getSku()))
                .findFirst()
                .map(DemandForecastRecord::getDemandPlan)
                .orElse(null);
        return new ForecastFactorDto(
                saved.getSku(),
                toDouble(saved.getSeasonalCoef()),
                toDouble(saved.getGainDays()),
                toDouble(saved.getSafetyDays()),
                toDouble(saved.getReturnRate()),
                toDouble(saved.getReturnReuseRate()),
                toDouble(plan)
        );
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    public record ForecastFactorDto(
            String sku,
            Double seasonalCoef,
            Double gainDays,
            Double safetyDays,
            Double returnRate,
            Double returnReuseRate,
            Double demandPlan
    ) {
    }

    public record FactorPatch(
            String field,
            Double value
    ) {
    }
}
