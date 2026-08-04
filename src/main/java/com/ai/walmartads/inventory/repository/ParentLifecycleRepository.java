package com.ai.walmartads.inventory.repository;

import com.ai.walmartads.inventory.domain.ParentLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentLifecycleRepository extends JpaRepository<ParentLifecycle, String> {
}
