package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.parser.ImportValues;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class SourceScanner {

    private static final Pattern DATE_DIRECTORY = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern INVENTORY_DATE = Pattern.compile("inventory-(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY_INVENTORY_DATE = Pattern.compile(
            "^沃尔玛PNK周报\\s+(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})\\s*\\.xlsx$",
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
        scanDailyDirectories(root, files);
        scanInventory(root, files);
        scanWeeklyInventory(files);
        scanProducts(root, files);
        scanSkuMappingWorkbook(files);
        scanHistoricalWorkbook(files);
        files.sort(Comparator
                .comparingInt((SourceFileCandidate item) -> priority(item.fileType()))
                .thenComparing(item -> item.path().toString()));
        return files;
    }

    private void scanDailyDirectories(Path root, List<SourceFileCandidate> files) throws IOException {
        Path configuredDailyRoot = root.resolve("date");
        Path dailyRoot = Files.isDirectory(configuredDailyRoot) ? configuredDailyRoot : root;
        try (Stream<Path> directories = Files.list(dailyRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                LocalDate reportDate = directoryDate(directory.getFileName().toString());
                if (reportDate == null) {
                    continue;
                }
                try (Stream<Path> contents = Files.list(directory)) {
                    for (Path path : contents.filter(Files::isRegularFile).toList()) {
                        if (path.getFileName().toString().startsWith("~$")) {
                            continue;
                        }
                        DailyFileDescriptor descriptor = dailyFile(path.getFileName().toString());
                        if (descriptor != null) {
                            files.add(new SourceFileCandidate(
                                    path,
                                    root.relativize(path),
                                    descriptor.fileType(),
                                    descriptor.reportKind(),
                                    reportDate,
                                    null
                            ));
                        }
                    }
                }
            }
        }
    }

    private void scanInventory(Path root, List<SourceFileCandidate> files) throws IOException {
        Path inventoryRoot = root.resolve("source").resolve("inventory");
        if (!Files.isDirectory(inventoryRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(inventoryRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("~$") || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    continue;
                }
                LocalDateTime snapshotAt = inventorySnapshot(name, Files.getLastModifiedTime(path));
                files.add(new SourceFileCandidate(
                        path,
                        root.relativize(path),
                        ImportFileType.INVENTORY,
                        null,
                        null,
                        snapshotAt
                ));
            }
        }
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

    private void scanWeeklyInventory(List<SourceFileCandidate> files) throws IOException {
        Path configured = properties.weeklyReportsRoot();
        if (configured == null) {
            return;
        }
        Path weeklyRoot = configured.toAbsolutePath().normalize();
        if (!Files.isDirectory(weeklyRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(weeklyRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("~$")) {
                    continue;
                }
                LocalDate reportDate = weeklyInventoryDate(name);
                if (reportDate == null) {
                    continue;
                }
                files.add(new SourceFileCandidate(
                        path,
                        Path.of("weekly-inventory").resolve(weeklyRoot.relativize(path)),
                        ImportFileType.WEEKLY_INVENTORY,
                        null,
                        reportDate,
                        reportDate.atTime(LocalTime.NOON)
                ));
            }
        }
    }

    private void scanHistoricalWorkbook(List<SourceFileCandidate> files) {
        Path configured = properties.historicalWorkbook();
        if (configured == null) {
            return;
        }
        Path path = configured.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || path.getFileName().toString().startsWith("~$")) {
            return;
        }
        files.add(new SourceFileCandidate(
                path,
                Path.of("history").resolve(path.getFileName()),
                ImportFileType.HISTORY,
                AdReportKind.ITEM_PERFORMANCE,
                null,
                null
        ));
    }

    private void scanSkuMappingWorkbook(List<SourceFileCandidate> files) {
        Path configured = properties.skuMappingWorkbook();
        if (configured == null) {
            return;
        }
        Path path = configured.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || path.getFileName().toString().startsWith("~$")) {
            return;
        }
        files.add(new SourceFileCandidate(
                path,
                Path.of("sku-mapping").resolve(path.getFileName()),
                ImportFileType.SKU_MAPPING,
                null,
                null,
                ImportValues.fileTime(lastModified(path).toInstant())
        ));
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 SKU 映射表修改时间：" + path, exception);
        }
    }

    private static DailyFileDescriptor dailyFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("itemkeywordperformance") && lower.endsWith(".csv")) {
            return new DailyFileDescriptor(ImportFileType.AD_REPORT, AdReportKind.ITEM_KEYWORD_PERFORMANCE);
        }
        if (lower.startsWith("itemperformance") && lower.endsWith(".csv")) {
            return new DailyFileDescriptor(ImportFileType.AD_REPORT, AdReportKind.ITEM_PERFORMANCE);
        }
        if (lower.startsWith("keywordperformance") && lower.endsWith(".csv")) {
            return new DailyFileDescriptor(ImportFileType.AD_REPORT, AdReportKind.KEYWORD_PERFORMANCE);
        }
        if (lower.startsWith("po_data") && lower.endsWith(".xlsx")) {
            return new DailyFileDescriptor(ImportFileType.ORDER, null);
        }
        return null;
    }

    private static LocalDate directoryDate(String name) {
        Matcher matcher = DATE_DIRECTORY.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static LocalDateTime inventorySnapshot(String fileName, FileTime fallback) {
        Matcher matcher = INVENTORY_DATE.matcher(fileName);
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), DateTimeFormatter.ISO_LOCAL_DATE).atTime(LocalTime.NOON);
            } catch (DateTimeParseException ignored) {
                // Fall through to filesystem timestamp.
            }
        }
        return ImportValues.fileTime(fallback.toInstant());
    }

    private static LocalDate weeklyInventoryDate(String fileName) {
        Matcher matcher = WEEKLY_INVENTORY_DATE.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int priority(ImportFileType type) {
        return switch (type) {
            case SKU_MAPPING -> 0;
            case INVENTORY -> 1;
            case WEEKLY_INVENTORY -> 2;
            case PRODUCT -> 3;
            case HISTORY -> 4;
            case ORDER -> 5;
            case AD_REPORT, AD_AUTO, AD_MANUAL -> 6;
        };
    }

    private record DailyFileDescriptor(ImportFileType fileType, AdReportKind reportKind) {
    }
}
