package com.ai.walmartads.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

@MappedSuperclass
public abstract class WorkspaceOwnedEntity {

    @TenantId
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    public Long getWorkspaceId() {
        return workspaceId;
    }
}
