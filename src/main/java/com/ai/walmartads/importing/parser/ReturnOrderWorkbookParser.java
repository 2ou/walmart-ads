package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ReturnOrderWorkbookParser {

    private static final String SHEET_NAME = "Return Order Details";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "CUSTOMER_ORDER_NO",
            "RMA_NO",
            "QUANTITY",
            "REQUEST_DATE",
            "ITEM_ID"
    );

    private final JsonPayloadWriter json;
    private final SkuResolver skuResolver;

    public ReturnOrderWorkbookParser(JsonPayloadWriter json, SkuResolver skuResolver) {
        this.json = json;
        this.skuResolver = skuResolver;
    }

    public ParsedRows<ReturnOrderRecord> parse(Path path, ImportFile importFile) throws IOException {
        LocalDate reportMonth = importFile.getReportDate();
        if (reportMonth == null) {
            throw new IllegalArgumentException("退款报表缺少文件月份：" + path);
        }
        List<ReturnOrderRecord> records = new ArrayList<>();
        Map<String, SkuResolver.ResolvedSku> skuCache = new LinkedHashMap<>();
        int sourceRows = 0;
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headers = headers(headerRow);
            require(headers);
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = values(row, headers);
                if (values.isEmpty()) {
                    continue;
                }
                sourceRows++;
                String customerOrderNo = values.get("CUSTOMER_ORDER_NO");
                String rmaNo = values.get("RMA_NO");
                String itemId = values.get("ITEM_ID");
                LocalDateTime requestAt = dateTime(values.get("REQUEST_DATE"));
                if (customerOrderNo == null || rmaNo == null || itemId == null || requestAt == null) {
                    continue;
                }
                LocalDate requestMonth = requestAt.toLocalDate().withDayOfMonth(1);
                SkuResolver.ResolvedSku resolved = skuCache.computeIfAbsent(
                        itemId,
                        key -> skuResolver.resolve(key, null)
                );
                records.add(new ReturnOrderRecord(
                        importFile,
                        rowIndex + 1,
                        businessKey(values),
                        requestMonth,
                        requestAt,
                        dateTime(values.get("CURRENT_STATUS_TIME")),
                        resolved.sku(),
                        resolved.parentSku(),
                        values,
                        json.write(values)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Map<String, Integer> headers(Row row) {
        if (row == null) {
            throw new IllegalArgumentException("退款报表没有表头");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        row.forEach(cell -> {
            String name = ImportValues.text(cell);
            if (name != null) {
                result.put(name.trim().toUpperCase(), cell.getColumnIndex());
            }
        });
        return result;
    }

    private static Map<String, String> values(Row row, Map<String, Integer> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, index) -> {
            String value = ImportValues.text(row.getCell(index));
            if (value != null) {
                result.put(name, value);
            }
        });
        return result;
    }

    private static void require(Map<String, Integer> headers) {
        REQUIRED_HEADERS.forEach(name -> {
            if (!headers.containsKey(name)) {
                throw new IllegalArgumentException("退款报表缺少字段：" + name);
            }
        });
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim())
                    .atZoneSameInstant(BUSINESS_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
    }

    private static String businessKey(Map<String, String> values) {
        String source = String.join("|",
                text(values, "RMA_NO"),
                text(values, "RETURN_ORDER_LINE_NO"),
                text(values, "ITEM_ID"),
                text(values, "CUSTOMER_ORDER_NO")
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    private static String text(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }
}
