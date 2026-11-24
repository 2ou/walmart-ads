package com.ai.walmartads.analytics.application;

import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardDataServiceVersionTest {
    @AfterEach void clear() { WorkspaceContext.clear(); }

    @Test void versionRequiresWorkspaceAndOnlyReadsItsLocalStatus() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var service = new DashboardDataService(jdbc);
        assertThatThrownBy(service::version).isInstanceOf(IllegalStateException.class);
        WorkspaceContext.set(new WorkspacePrincipal(7, "STORE", "ACTIVE", "7", "Store", "ST", true, 1));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
            SqlParameterSource params = call.getArgument(1);
            assertThat(params.getValue("workspaceId")).isEqualTo(7L);
            return List.of(Map.of("sync_category", "SALES", "last_status", "SUCCESS"));
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenAnswer(call -> {
            SqlParameterSource params = call.getArgument(1);
            assertThat(params.getValue("workspaceId")).isEqualTo(7L);
            return 42L;
        });
        var version = service.version();
        assertThat(version).containsEntry("workspaceId", 7L).containsEntry("activeImportId", 42L).containsKey("visibleDate");
        assertThat(((Map<?, ?>) version.get("SALES")).get("status")).isEqualTo("SUCCESS");
        verify(jdbc, times(1)).queryForList(anyString(), any(SqlParameterSource.class));
        verify(jdbc, times(1)).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }
}
