package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.domain.ParentLifecycle;
import com.ai.walmartads.inventory.repository.ParentLifecycleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ParentLifecycleService {

    private final ParentLifecycleRepository repository;

    public ParentLifecycleService(ParentLifecycleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Set<String> clearanceParents() {
        return repository.findAll().stream()
                .filter(ParentLifecycle::isClearance)
                .map(ParentLifecycle::getParentSku)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public LifecycleView update(String parentSku, Boolean clearance, String seasonType) {
        ParentLifecycle lifecycle = repository.findById(parentSku)
                .orElseGet(() -> new ParentLifecycle(parentSku));
        if (clearance != null) {
            lifecycle.setClearance(clearance);
        }
        if (seasonType != null) {
            lifecycle.setSeasonType(seasonType);
        }
        repository.save(lifecycle);
        return view(lifecycle);
    }

    @Transactional(readOnly = true)
    public Map<String, LifecycleView> viewsByParent() {
        return repository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ParentLifecycle::getParentSku,
                        this::view
                ));
    }

    @Transactional(readOnly = true)
    public LifecycleView viewFor(String parentSku) {
        return repository.findById(parentSku)
                .map(this::view)
                .orElseGet(() -> LifecycleView.defaultFor(parentSku));
    }

    private LifecycleView view(ParentLifecycle lifecycle) {
        return new LifecycleView(
                lifecycle.getParentSku(),
                lifecycle.isClearance(),
                lifecycle.isAllSeason(),
                lifecycle.getSeasonType(),
                lifecycle.getUpdatedAt().toString()
        );
    }

    public record LifecycleView(String parentSku, boolean clearance, boolean allSeason, String seasonType, String updatedAt) {
        public static LifecycleView defaultFor(String parentSku) {
            return new LifecycleView(parentSku, false, false, ParentLifecycle.SEASONAL, null);
        }
    }
}
