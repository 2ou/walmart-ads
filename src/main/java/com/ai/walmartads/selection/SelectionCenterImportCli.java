package com.ai.walmartads.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Imports a collected file directly, without starting the workbench or its background jobs. */
public final class SelectionCenterImportCli {
    private SelectionCenterImportCli() {}

    public static void main(String[] args) throws Exception {
        boolean migrate = args.length == 2 && "--migrate".equals(args[0]);
        if (!(args.length == 1 || migrate)) {
            throw new IllegalArgumentException("Usage: SelectionCenterImportCli [--migrate] /absolute/snapshot.json");
        }
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SelectionSnapshot snapshot;
        try (InputStream in = Files.newInputStream(Path.of(args[args.length - 1]))) {
            snapshot = mapper.readValue(in, SelectionSnapshot.class);
        }
        SelectionCenterRules.validate(snapshot);
        SelectionCenterRules.groups(snapshot);

        String url = setting("WALMART_ADS_DB_URL", "jdbc:mysql://127.0.0.1:3306/walmart_ads"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        var dataSource = new DriverManagerDataSource(url, setting("WALMART_ADS_DB_USERNAME", "root"),
                setting("WALMART_ADS_DB_PASSWORD", "root"));
        if (migrate) applySelectionMigration(dataSource);
        var store = new SelectionCenterStore(new JdbcTemplate(dataSource));
        var service = new SelectionCenterService(store, mapper, new DataSourceTransactionManager(dataSource));
        System.out.println(mapper.writeValueAsString(service.ingest(snapshot)));
    }

    private static void applySelectionMigration(DriverManagerDataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("89").load();
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length == 0) return;
        if (pending.length != 1 || !"89".equals(pending[0].getVersion().getVersion())) {
            throw new IllegalStateException("数据库还存在其他待执行迁移，请先通过正常流程处理。此次未运行迁移。");
        }
        flyway.migrate();
    }

    private static String setting(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
