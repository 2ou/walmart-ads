package com.ai.walmartads.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePrimaryIdentityTest {

    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private WorkspaceAccountService accounts;

    @BeforeEach
    void setup() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:workspace_primary_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table wm_ads_workspace (
                    id bigint primary key, username varchar(64), password_hash varchar(255),
                    status varchar(24), session_version bigint, store_id varchar(80), store_name varchar(160),
                    store_abbreviation varchar(12), sku_prefixes varchar(1000),
                    mcp_base_url varchar(500), mcp_key varchar(512), mcp_key_last4 varchar(4),
                    supply_chain_enabled boolean, primary_workspace boolean,
                    initialization_status varchar(32), initialization_message varchar(1000)
                )
                """);
        jdbc.execute("""
                create table wm_ads_workspace_audit (
                    workspace_id bigint, username varchar(64), event_type varchar(64), success boolean,
                    summary varchar(1000), ip_address varchar(64), user_agent varchar(500)
                )
                """);
        jdbc.update("""
                insert into wm_ads_workspace
                    (id, username, status, primary_workspace, password_hash) values
                    (7, 'Demo shop', 'ACTIVE', true, ?), (8, 'Sample shop', 'ACTIVE', false, ?)
                """, passwords.encode("Original-primary"), passwords.encode("Original-sample"));
        accounts = new WorkspaceAccountService(jdbc, passwords, new ObjectMapper(), null, null);
    }

    @AfterEach
    void cleanup() {
        WorkspaceContext.clear();
        dataSource.destroy();
    }

    @Test
    void managementUsesPersistedPrimaryRole() {
        assertThat(accounts.isPrimaryWorkspace(7L)).isTrue();
        assertThat(accounts.isPrimaryWorkspace(8L)).isFalse();
        WorkspaceContext.set(principal(7, "Renamed shop"));
        assertThat(accounts.managedAccounts()).filteredOn(WorkspaceAccountService.ManagedAccountView::primaryWorkspace)
                .extracting(WorkspaceAccountService.ManagedAccountView::username).containsExactly("Demo shop");
        WorkspaceContext.set(principal(8, "Demo shop"));
        assertThatThrownBy(accounts::managedAccounts).hasMessageContaining("仅主工作区管理员");
    }

    @Test
    void localResetChangesOnlyThePersistedPrimaryAccount() {
        var request = new WorkspaceAccountService.PasswordResetRequest("Example-new-password", "Example-new-password");
        accounts.emergencyResetPrimary(request, "127.0.0.1", "test");
        assertThat(passwords.matches("Example-new-password", passwordHash(7))).isTrue();
        assertThat(passwords.matches("Original-sample", passwordHash(8))).isTrue();
    }

    private String passwordHash(long workspaceId) {
        return jdbc.queryForObject("select password_hash from wm_ads_workspace where id=?", String.class, workspaceId);
    }

    private static WorkspacePrincipal principal(long workspaceId, String name) {
        return new WorkspacePrincipal(workspaceId, name, "ACTIVE", "demo", name, "DEMO", false, 1L);
    }
}
