package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.parser.ImportValues;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class SourceScanner {

    private static final Pattern DOMESTIC_INVENTORY_DATE = Pattern.compile(".*?(\\d{4})(\\d{2})(\\d{2}).*");
    private static final Pattern RETURN_ORDER_MONTH = Pattern.compile(
            "^Customer returns-(\\d{4})(\\d{2})\\.xlsx$",
            Pattern.CASE_INSENSITIVE
    );

    private final WalmartAdsProperties properties;

    public SourceScanner(WalmartAdsProperties properties) {
        this.properties = properties;
    }

    public List<SourceFileCandidate> scan() throws IOException {
        Path root = properties.dataRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("数据目录不存在：" + root);
        }
        List<SourceFileCandidate> files = new ArrayList<>();
        // 扫描入口 = 6 类：产品资料 / 月度退款 / 生产订单 / 货件 / 物流信息登记 / 国内仓库存。
        //
        // 已退役（不再扫描，ImportFileType 枚举保留仅为读回历史 import_file 行）：
        //   • SKU_MAPPING —— WFS 上传模板（2026-09-28 移出）。数据源换成领星 MCP 在线商品，
        //     由 WalmartListingSyncService 每小时顺带写入，见 SkuMappingSyncService。
        //   • HISTORY —— PNK 每日销售数据统计.xlsx（2026-09-28 移出）
        //   • DEMAND_FORECAST —— source/demand-forecast/ 目录已不存在（死链路）
        //   • ORDER / AD_* / INVENTORY / WEEKLY_INVENTORY / WALMART_LISTING / CAMPAIGN_SNAPSHOT
        //     —— 分别由领星 MCP 在线同步或已下线（见 FileImportProcessor 注册表注释）
        scanProducts(root, files);
        scanReturnOrders(root, files);
        scanProductionOrder(root, files);
        scanShipments(root, files);
        scanDomesticInventory(root, files);
        files.sort(Comparator
                .comparingInt((SourceFileCandidate item) -> priority(item.fileType()))
                .thenComparing(item -> item.path().toString()));
        return files;
    }

    /** Classifies one browser-uploaded workbook without scanning the shared server directory. */
    public SourceFileCandidate uploadedCandidate(Path path, ImportSource source) throws IOException {
        if (path == null || source == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("上传文件或导入类型无效");
        }
        String name = path.getFileName().toString();
        if (name.startsWith("~$") || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 工作簿");
        }
        LocalDate reportDate = null;
        LocalDateTime snapshotAt = ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant());
        if (source == ImportSource.RETURN_ORDER) {
            Matcher matcher = RETURN_ORDER_MONTH.matcher(name);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("退款文件名必须为 Customer returns-YYYYMM.xlsx");
            }
            reportDate = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))).atDay(1);
            snapshotAt = null;
        } else if (source == ImportSource.DOMESTIC_INVENTORY) {
            snapshotAt = domesticInventorySnapshotAt(name, path);
            reportDate = snapshotAt.toLocalDate();
        }
        return new SourceFileCandidate(path, Path.of("workspace-upload").resolve(name), source.fileType(),
                null, reportDate, snapshotAt);
    }

    private void scanProducts(Path root, List<SourceFileCandidate> files) throws IOException {
        Path uploadRoot = root.resolve("source").resolve("upload");
        if (!Files.isDirectory(uploadRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(uploadRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("~$") || !name.endsWith("-upload.xlsx")) {
                    continue;
                }
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        ImportFileType.PRODUCT,
                        null,
                        null,
                        ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant())
                ));
            }
        }
    }

    private void scanReturnOrders(Path root, List<SourceFileCandidate> files) throws IOException {
        Path returnRoot = root.resolve("source").resolve("Return_Orders");
        if (!Files.isDirectory(returnRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(returnRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("~$")) {
                    continue;
                }
                Matcher matcher = RETURN_ORDER_MONTH.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                LocalDate reportMonth;
                try {
                    reportMonth = YearMonth.of(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                    ).atDay(1);
                } catch (RuntimeException exception) {
                    continue;
                }
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        ImportFileType.RETURN_ORDER,
                        null,
                        reportMonth,
                        null
                ));
            }
        }
    }

    private void scanProductionOrder(Path root, List<SourceFileCandidate> files) throws IOException {
        Path productionRoot = root.resolve("source").resolve("生产订单");
        if (!Files.isDirectory(productionRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(productionRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("~$") || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    continue;
                }
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        ImportFileType.PRODUCTION_ORDER,
                        null,
                        null,
                        ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant())
                ));
            }
        }
    }

    private void scanShipments(Path root, List<SourceFileCandidate> files) throws IOException {
        Path shipmentRoot = root.resolve("source").resolve("货件");
        if (!Files.isDirectory(shipmentRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(shipmentRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith("~$") || !lower.endsWith(".xlsx")) {
                    continue;
                }
                ImportFileType type;
                if (name.contains("物流信息登记")) {
                    type = ImportFileType.SHIPMENT_LOGISTICS;
                } else if (name.contains("货件")) {
                    type = ImportFileType.SHIPMENT;
                } else {
                    continue;
                }
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        type,
                        null,
                        null,
                        ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant())
                ));
            }
        }
    }

    private void scanDomesticInventory(Path root, List<SourceFileCandidate> files) throws IOException {
        Path domesticRoot = root.resolve("source").resolve("国内仓库存");
        if (!Files.isDirectory(domesticRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(domesticRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("~$") || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    continue;
                }
                LocalDateTime snapshotAt = domesticInventorySnapshotAt(name, path);
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        ImportFileType.DOMESTIC_INVENTORY,
                        null,
                        snapshotAt.toLocalDate(),
                        snapshotAt
                ));
            }
        }
    }

    private static LocalDateTime domesticInventorySnapshotAt(String fileName, Path path) {
        Matcher matcher = DOMESTIC_INVENTORY_DATE.matcher(fileName);
        if (matcher.matches()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                ).atTime(LocalTime.NOON);
            } catch (RuntimeException ignored) {
                // Fall through to file modification time.
            }
        }
        try {
            return ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read domestic inventory modification time: " + path, exception);
        }
    }

    private static int priority(ImportFileType type) {
        return switch (type) {
            case SKU_MAPPING -> 0;
            case INVENTORY -> 1;
            case WEEKLY_INVENTORY -> 2;
            case PRODUCT -> 3;
            case DEMAND_FORECAST -> 3;
            case WALMART_LISTING -> 3;
            case PRODUCTION_ORDER -> 3;
            case SHIPMENT, SHIPMENT_LOGISTICS -> 3;
            case DOMESTIC_INVENTORY -> 3;
            case CAMPAIGN_SNAPSHOT -> 3;
            case HISTORY -> 4;
            case ORDER -> 5;
            case AD_REPORT, AD_AUTO, AD_MANUAL -> 6;
            case RETURN_ORDER -> 7;
            // 领星 MCP 同步产物，不经文件扫描进来（扫描结果里不会出现），给最低优先级仅为穷尽枚举。
            case LINGXING_ORDER -> 9;
        };
    }
}
