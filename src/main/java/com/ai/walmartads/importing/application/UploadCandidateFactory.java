package com.ai.walmartads.importing.application;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds import metadata for a workbook uploaded by the current workspace. */
@Component
public class UploadCandidateFactory {

    private static final Pattern DOMESTIC_INVENTORY_DATE = Pattern.compile(".*?(\\d{4})(\\d{2})(\\d{2}).*");
    private static final Pattern RETURN_ORDER_MONTH = Pattern.compile(
            "^Customer returns-(\\d{4})(\\d{2})\\.xlsx$",
            Pattern.CASE_INSENSITIVE
    );

    /** Classifies a browser upload held in request memory; no server-local file is created. */
    public SourceFileCandidate create(String fileName, ImportSource source, LocalDateTime uploadedAt) {
        if (fileName == null || source == null) {
            throw new IllegalArgumentException("上传文件或导入类型无效");
        }
        String name = Path.of(fileName).getFileName().toString();
        if (name.startsWith("~$") || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx 工作簿");
        }
        LocalDate reportDate = null;
        LocalDateTime snapshotAt = uploadedAt == null ? LocalDateTime.now() : uploadedAt;
        if (source == ImportSource.RETURN_ORDER) {
            Matcher matcher = RETURN_ORDER_MONTH.matcher(name);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("退款文件名必须为 Customer returns-YYYYMM.xlsx");
            }
            reportDate = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))).atDay(1);
            snapshotAt = null;
        } else if (source == ImportSource.DOMESTIC_INVENTORY) {
            snapshotAt = domesticInventorySnapshotAt(name, snapshotAt);
            reportDate = snapshotAt.toLocalDate();
        }
        Path logicalPath = Path.of("database-upload").resolve(name);
        return new SourceFileCandidate(logicalPath, logicalPath, source.fileType(), null, reportDate, snapshotAt);
    }

    private static LocalDateTime domesticInventorySnapshotAt(String fileName, LocalDateTime fallback) {
        Matcher matcher = DOMESTIC_INVENTORY_DATE.matcher(fileName);
        if (matcher.matches()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                ).atTime(LocalTime.NOON);
            } catch (RuntimeException ignored) {
                // Fall through to upload time.
            }
        }
        return fallback;
    }
}
