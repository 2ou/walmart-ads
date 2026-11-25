package com.ai.walmartads.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

/**
 * Imports workspace finance workbooks directly into MySQL without starting the web server.
 */
public final class FinanceImportCli {

    private FinanceImportCli() {
    }

    public static void main(String[] args) throws Exception {
        boolean migrate = false;
        long workspaceId = 1L;
        String account = null;
        String dirArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--migrate".equals(arg)) {
                migrate = true;
            } else if ("--workspace".equals(arg) && i + 1 < args.length) {
                workspaceId = Long.parseLong(args[++i]);
            } else if ("--account".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("请为 --account 提供账表中的财务账号。");
                }
                account = args[++i];
            } else {
                dirArg = arg;
            }
        }
        if (dirArg == null || dirArg.isBlank()) {
            throw new IllegalArgumentException(
                    "Usage: FinanceImportCli [--migrate] [--workspace <id>] [--account <code>] <finance_directory>");
        }

        String url = setting("WALMART_ADS_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/walmart_ads"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                setting("WALMART_ADS_DB_USERNAME", "root"),
                setting("WALMART_ADS_DB_PASSWORD", "root")
        );

        if (migrate) {
            applyFinanceMigration(dataSource);
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        FinanceImportService service = new FinanceImportService(
                jdbc,
                new FinanceWorkbookParser(),
                new DataSourceTransactionManager(dataSource)
        );
        FinanceImportService.DirectoryImportResult result = service.importDirectory(Path.of(dirArg), workspaceId, account);

        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        System.out.println(mapper.writeValueAsString(result));
    }

    private static void applyFinanceMigration(DriverManagerDataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("90")
                .load();
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length == 0) {
            return;
        }
        if (pending.length != 1 || !"90".equals(pending[0].getVersion().getVersion())) {
            throw new IllegalStateException("数据库存在非 V90 的其他待执行迁移，已中止自动迁移。");
        }
        flyway.migrate();
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
