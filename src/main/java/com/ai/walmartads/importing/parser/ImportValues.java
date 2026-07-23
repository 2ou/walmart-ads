package com.ai.walmartads.importing.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ImportValues {

    private static final DataFormatter FORMATTER = new DataFormatter(Locale.US);
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    );

    private ImportValues() {
    }

    public static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        String value = FORMATTER.formatCellValue(cell);
        return blankToNull(value);
    }

    public static String text(Map<String, String> row, String key) {
        return blankToNull(row.get(key));
    }

    public static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        String normalized = value.trim()
                .replace("$", "")
                .replace(",", "")
                .replace("%", "");
        if (normalized.isBlank() || normalized.equalsIgnoreCase("N/A") || normalized.equalsIgnoreCase("NA")) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal parsed = new BigDecimal(normalized);
            return value.contains("%") ? parsed.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP) : parsed;
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    public static long whole(String value) {
        return decimal(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public static LocalDate date(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        return date(text(cell));
    }

    public static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next known source format.
            }
        }
        return null;
    }

    public static LocalDateTime fileTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    public static String parentSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        int separator = sku.indexOf('-');
        return separator > 0 ? sku.substring(0, separator) : sku;
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String first(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).findFirst().orElse(null);
        }
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    public static List<String> list(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        String item = blankToNull(String.valueOf(value));
        return item == null ? List.of() : List.of(item);
    }

    public static void addPreservingDuplicates(Map<String, Object> target, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        Object existing = target.get(key);
        if (existing == null) {
            target.put(key, value);
        } else if (existing instanceof List<?> existingList) {
            List<String> copy = new ArrayList<>(existingList.stream().map(String::valueOf).toList());
            copy.add(value);
            target.put(key, copy);
        } else {
            target.put(key, new ArrayList<>(List.of(String.valueOf(existing), value)));
        }
    }
}
