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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析沃尔玛「Customer Returns」退货模板（Customer returns-YYYYMM.xlsx）。
 *
 * <p>与旧版 Return_Orders 模板的关键差异：
 * <ul>
 *   <li>直接带 {@code Seller SKU}（与产品库 sku 完全一致，含 -WFS 后缀），
 *       不再依赖 ITEM_ID → sku_mapping 反查，归因更稳、不丢数据。</li>
 *   <li>状态字段为 {@code Return Invoice Status}（如 RETURN_INVOICED），
 *       无旧版的 Refunded:/Cancelled: 前缀，退款率判定改为等于 RETURN_INVOICED。</li>
 *   <li>退货运费单列 {@code WFS Return Processing Fee}；退款承担方用 {@code Fault}(SELLER/WFS)。</li>
 * </ul>
 */
@Component
public class ReturnOrderWorkbookParser {

    private static final String SHEET_NAME = "Customer Returns";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "CUSTOMERORDERID",
            "RMANUMBER",
            "SELLERSKU",
            "QTYRETURNED",
            "RETURNINITIATIONDATE"
    );

    private final JsonPayloadWriter json;

    public ReturnOrderWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<ReturnOrderRecord> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, importFile, path.toString());
        }
    }

    public ParsedRows<ReturnOrderRecord> parse(byte[] content, ImportFile importFile) throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            return parse(input, importFile, importFile.getFileName());
        }
    }

    private ParsedRows<ReturnOrderRecord> parse(InputStream input, ImportFile importFile, String sourceName)
            throws IOException {
        LocalDate reportMonth = importFile.getReportDate();
        if (reportMonth == null) {
            throw new IllegalArgumentException("退款报表缺少文件月份：" + sourceName);
        }
        List<ReturnOrderRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
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
                String customerOrderNo = values.get("CUSTOMERORDERID");
                String rmaNo = values.get("RMANUMBER");
                String sellerSku = values.get("SELLERSKU");
                LocalDateTime requestAt = dateTime(values.get("RETURNINITIATIONDATE"));
                if (customerOrderNo == null || customerOrderNo.isBlank()
                        || rmaNo == null || rmaNo.isBlank()
                        || sellerSku == null || sellerSku.isBlank()
                        || requestAt == null) {
                    continue;
                }
                LocalDate requestMonth = requestAt.toLocalDate().withDayOfMonth(1);
                String parentSku = parentSkuOf(sellerSku);
                records.add(new ReturnOrderRecord(
                        importFile,
                        rowIndex + 1,
                        businessKey(values),
                        requestMonth,
                        requestAt,
                        dateTime(values.get("DATEPROCESSEDINRC")),
                        sellerSku,
                        parentSku,
                        values,
                        json.write(values)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    /** Seller SKU 形如 PNK0079-C-Beige-M-WFS，父款 = 首个 '-' 之前的一段。 */
    private static String parentSkuOf(String sellerSku) {
        int cut = sellerSku.indexOf('-');
        return cut > 0 ? sellerSku.substring(0, cut) : sellerSku;
    }

    private static Map<String, Integer> headers(Row row) {
        if (row == null) {
            throw new IllegalArgumentException("退款报表没有表头");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        row.forEach(cell -> {
            String name = ImportValues.text(cell);
            if (name != null) {
                result.put(normalizeHeader(name), cell.getColumnIndex());
            }
        });
        return result;
    }

    /** 归一化表头：去空格/连字符/括号/下划线，仅保留 A-Z0-9，便于稳定取列。 */
    private static String normalizeHeader(String name) {
        return name.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
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
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(v).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T'),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String businessKey(Map<String, String> values) {
        String source = String.join("|",
                text(values, "CUSTOMERORDERID"),
                text(values, "RMANUMBER"),
                text(values, "RMALINENUMBER")
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
        String value = values.get(key);
        return value == null ? "" : value;
    }
}
