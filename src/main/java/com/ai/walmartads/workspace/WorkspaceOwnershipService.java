package com.ai.walmartads.workspace;

import com.ai.walmartads.importing.application.ImportSource;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Enforces the configured SKU/style prefixes before non-LingXing data enters a workspace. */
@Service
public class WorkspaceOwnershipService {

    private static final Set<String> OWNERSHIP_HEADERS = Set.of(
            "sku", "msku", "parentsku", "parentmsku", "localsku", "商品sku", "产品sku", "款号", "货号");
    private static final int HEADER_SCAN_ROWS = 40;
    private static final int MAX_IDENTIFIERS = 30;

    private final JdbcTemplate jdbc;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public WorkspaceOwnershipService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> currentPrefixes() {
        long workspaceId = WorkspaceContext.requireId();
        String configured = jdbc.queryForObject(
                "select sku_prefixes from wm_ads_workspace where id=?", String.class, workspaceId);
        if (!StringUtils.hasText(configured)) return List.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public boolean belongsToCurrent(String... identifiers) {
        List<String> prefixes = currentPrefixes();
        if (prefixes.isEmpty()) return true;
        List<String> values = Arrays.stream(identifiers == null ? new String[0] : identifiers)
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .toList();
        return !values.isEmpty() && values.stream().allMatch(value ->
                prefixes.stream().anyMatch(value::startsWith));
    }

    /** Rejects a workbook and stores only database audit metadata when it contains another prefix. */
    public void verifyUploadedWorkbook(Path workbookPath, ImportSource source, String originalFilename)
            throws IOException {
        try (InputStream input = Files.newInputStream(workbookPath)) {
            verifyUploadedWorkbook(input, source, originalFilename);
        }
    }

    public void verifyUploadedWorkbook(byte[] content, ImportSource source, String originalFilename)
            throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            verifyUploadedWorkbook(input, source, originalFilename);
        }
    }

    private void verifyUploadedWorkbook(InputStream input, ImportSource source, String originalFilename)
            throws IOException {
        List<String> prefixes = currentPrefixes();
        if (prefixes.isEmpty()) return;
        Set<String> identifiers = workbookIdentifiers(input);
        if (identifiers.isEmpty()) return;
        List<String> mismatches = identifiers.stream()
                .filter(value -> prefixes.stream().noneMatch(value.toUpperCase(Locale.ROOT)::startsWith))
                .limit(MAX_IDENTIFIERS)
                .toList();
        if (mismatches.isEmpty()) return;
        String reason = "SKU/款号前缀与当前工作区不匹配；允许前缀：" + String.join(", ", prefixes);
        record(source.name(), originalFilename, mismatches, reason, null, null);
        throw new IllegalArgumentException(reason + "。已记录待归属审计，未保存原文件，未写入业务数据");
    }

    public void quarantineRecord(String sourceType, String sourceKey, String rawPayload, String... identifiers) {
        List<String> values = Arrays.stream(identifiers == null ? new String[0] : identifiers)
                .filter(StringUtils::hasText).limit(MAX_IDENTIFIERS).toList();
        record(sourceType, sourceKey, values, "SKU/款号前缀与当前工作区不匹配", null, rawPayload);
    }

    public List<UnassignedView> currentPending() {
        return jdbc.query("""
                select id, source_type, source_key, identifiers, reason, status, created_at
                  from wm_ads_workspace_unassigned_record
                 where workspace_id=? and status='PENDING'
                 order by created_at desc limit 100
                """, (rs, rowNum) -> new UnassignedView(
                rs.getLong("id"), rs.getString("source_type"), rs.getString("source_key"),
                rs.getString("identifiers"), rs.getString("reason"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime()), WorkspaceContext.requireId());
    }

    private Set<String> workbookIdentifiers(InputStream input) throws IOException {
        Set<String> identifiers = new LinkedHashSet<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                // Walmart templates contain hidden metadata sheets whose cells describe the SKU field
                // (Required / String / examples). They are schema definitions, not merchant identifiers.
                if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) continue;
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int lastHeaderCandidate = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS - 1);
                Row ownershipHeader = null;
                List<Integer> ownershipColumns = List.of();
                for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastHeaderCandidate; rowIndex++) {
                    Row header = sheet.getRow(rowIndex);
                    if (header == null) continue;
                    List<Integer> columns = new ArrayList<>();
                    header.forEach(cell -> {
                        if (isOwnershipHeader(formatter.formatCellValue(cell))) columns.add(cell.getColumnIndex());
                    });
                    if (columns.isEmpty()) continue;
                    // Templates often repeat the friendly header and then the machine header. The last
                    // matching row is the one immediately above the field description and actual data.
                    ownershipHeader = header;
                    ownershipColumns = columns;
                }
                if (ownershipHeader != null) {
                    for (int dataIndex = ownershipHeader.getRowNum() + 1;
                         dataIndex <= sheet.getLastRowNum(); dataIndex++) {
                        Row row = sheet.getRow(dataIndex);
                        if (row == null) continue;
                        for (int column : ownershipColumns) {
                            String value = formatter.formatCellValue(row.getCell(column)).trim();
                            // Walmart's field-description row is prose and can be hundreds of characters.
                            // Real SKU/style identifiers in these imports are capped well below this limit.
                            if (StringUtils.hasText(value) && value.length() <= 100) identifiers.add(value);
                        }
                    }
                }
            }
        }
        return identifiers;
    }

    private static boolean isOwnershipHeader(String value) {
        if (!StringUtils.hasText(value)) return false;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-/#()（）]+", "");
        return OWNERSHIP_HEADERS.contains(normalized);
    }

    private void record(String sourceType, String sourceKey, List<String> identifiers, String reason,
                        String quarantinedPath, String rawPayload) {
        jdbc.update("""
                insert into wm_ads_workspace_unassigned_record
                    (workspace_id, source_type, source_key, identifiers, reason, quarantined_path, raw_payload)
                values (?, ?, ?, ?, ?, ?, ?)
                """, WorkspaceContext.requireId(), sourceType, truncate(sourceKey, 500),
                truncate(String.join(", ", identifiers), 2000), truncate(reason, 1000),
                truncate(quarantinedPath, 1000), rawPayload);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record UnassignedView(long id, String sourceType, String sourceKey, String identifiers,
                                 String reason, String status, LocalDateTime createdAt) {
    }
}
