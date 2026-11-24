package com.ai.walmartads.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceHourlyMigrationTest {
    @Test
    void updatesOnlyCoreCadenceAndPreservesSwitchesAndMinutes() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:hourly_migration;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table wm_ads_workspace_sync_setting (sync_category varchar(32), cadence varchar(32), enabled int, stagger_minute int)");
        jdbc.update("insert into wm_ads_workspace_sync_setting values ('PRODUCTS','SIX_HOURLY',1,10),('INVENTORY','SIX_HOURLY',0,10),('SALES','SIX_HOURLY',1,25),('REVIEWS','DAILY',1,50),('ADS','HOURLY',1,40),('SALES','CUSTOM',1,12)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V88__hourly_core_mcp_sync.sql")).execute(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from wm_ads_workspace_sync_setting where sync_category in ('PRODUCTS','INVENTORY','SALES') and cadence='HOURLY'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select enabled from wm_ads_workspace_sync_setting where sync_category='INVENTORY'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select stagger_minute from wm_ads_workspace_sync_setting where sync_category='SALES' and cadence='HOURLY'", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("select cadence from wm_ads_workspace_sync_setting where sync_category='REVIEWS'", String.class)).isEqualTo("DAILY");
        assertThat(jdbc.queryForObject("select count(*) from wm_ads_workspace_sync_setting where cadence='CUSTOM'", Integer.class)).isEqualTo(1);
    }
}
