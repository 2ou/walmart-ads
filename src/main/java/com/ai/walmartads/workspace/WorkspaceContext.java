package com.ai.walmartads.workspace;

import java.util.Optional;
import java.util.function.Supplier;

public final class WorkspaceContext {

    private static final ThreadLocal<WorkspacePrincipal> CURRENT = new ThreadLocal<>();

    private WorkspaceContext() {
    }

    public static Optional<WorkspacePrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static WorkspacePrincipal requirePrincipal() {
        WorkspacePrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException("当前操作未绑定工作区");
        }
        return principal;
    }

    public static long requireId() {
        return requirePrincipal().workspaceId();
    }

    public static void set(WorkspacePrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callAs(WorkspacePrincipal principal, Supplier<T> action) {
        WorkspacePrincipal previous = CURRENT.get();
        try {
            CURRENT.set(principal);
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(WorkspacePrincipal principal, Runnable action) {
        callAs(principal, () -> {
            action.run();
            return null;
        });
    }
}
