package com.ai.walmartads.workspace;

import com.ai.walmartads.config.LingXingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceLingXingConfigServiceTest {

    @Test
    void readsOnlyTheCurrentActiveWorkspaceAndItsAdvertisers() {
        var database = new SingleConnectionDataSource(
                "jdbc:h2:mem:workspace_lingxing_" + UUID.randomUUID(), "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("""
                    create table wm_ads_workspace (
                        id bigint primary key, mcp_base_url varchar(255), mcp_key varchar(255),
                        store_id varchar(40), status varchar(16)
                    )
                    """);
            jdbc.execute("""
                    create table wm_ads_workspace_advertiser (workspace_id bigint, advertiser_id bigint)
                    """);
            jdbc.update("""
                    insert into wm_ads_workspace values
                        (1, 'https://sample.test/mcp', 'test-key-a', '101', 'ACTIVE'),
                        (2, null, 'test-key-b', '102', 'ACTIVE'),
                        (3, null, 'test-key-c', '103', 'DISABLED')
                    """);
            jdbc.update("insert into wm_ads_workspace_advertiser values (1, 201), (3, 203)");

            var deployment = new LingXingProperties(
                    true, "https://bootstrap.test/mcp", "test-key", null, 65, "999", 0,
                    List.of(299L), 0, null, null, 0, null, null);
            var service = new WorkspaceLingXingConfigService(jdbc, deployment);

            assertThat(service.current().configured()).isFalse();
            assertThat(WorkspaceContext.callAs(principal(1), service::current))
                    .isEqualTo(new WorkspaceLingXingConfigService.Config(
                            1, "https://sample.test/mcp", "test-key-a", "101", 65, List.of(201L)));
            assertThat(WorkspaceContext.callAs(principal(2), service::current))
                    .isEqualTo(new WorkspaceLingXingConfigService.Config(
                            2, "https://bootstrap.test/mcp", "test-key-b", "102", 65, List.of()));
            for (long id : List.of(3L, 4L)) {
                var missing = WorkspaceContext.callAs(principal(id), service::current);
                assertThat(missing.configured()).isFalse();
                assertThat(missing.storeId()).isNull();
                assertThat(missing.apiKey()).isNull();
                assertThat(missing.advertiserIds()).isEmpty();
            }
        } finally {
            database.destroy();
        }
    }

    private static WorkspacePrincipal principal(long id) {
        return new WorkspacePrincipal(id, "sample", "ACTIVE", null, null, null, false, 0);
    }
}
