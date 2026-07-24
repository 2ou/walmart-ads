package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyInventoryWorkbookParserTest {

    @TempDir
    Path temp;

    @Test
    void parsesWfsInventoryWithFilenameSnapshotAndEvaluatedFormulas() throws Exception {
        Path path = temp.resolve("沃尔玛PNK周报 2025.04.28.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("周报");
            Sheet inventory = workbook.createSheet("WFS库存");
            List<String> headers = List.of(
                    "Item name", "GTIN", "Walmart item ID", "SKU", "Status",
                    "Daily sales", "Daily units sold", "Available to sell", "Reservations",
                    "Inbound units", "Days of supply", "Out-of-stock date",
                    "Suggested units to send", "Brand", "品号2"
            );
            Row header = inventory.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            Row row = inventory.createRow(1);
            row.createCell(0).setCellValue("Women's Dress");
            row.createCell(1).setCellValue("00012345678901");
            row.createCell(2).setCellValue("987654321");
            row.createCell(3).setCellValue("PNK001-C-Black-M-WFS");
            row.createCell(4).setCellValue("Published");
            row.createCell(5).setCellValue(120.5);
            row.createCell(6).setCellValue(3);
            row.createCell(7).setCellFormula("30+5");
            row.createCell(8).setCellValue(2);
            row.createCell(9).setCellValue(8);
            row.createCell(10).setCellValue(14);
            row.createCell(11).setCellValue("5/12/25");
            row.createCell(12).setCellValue(25);
            row.createCell(13).setCellValue("PNK");
            row.createCell(14).setCellFormula("\"PNK001\"");
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }

        LocalDateTime snapshotAt = LocalDate.of(2025, 4, 28).atTime(12, 0);
        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.WEEKLY_INVENTORY,
                null,
                path.getFileName().toString(),
                path.toString(),
                "0".repeat(64),
                Files.size(path),
                LocalDateTime.now(),
                LocalDate.of(2025, 4, 28),
                snapshotAt
        );

        ParsedRows<InventorySnapshot> parsed = new WeeklyInventoryWorkbookParser(
                new JsonPayloadWriter(new ObjectMapper())
        ).parse(path, importFile);

        assertThat(parsed.sourceRowCount()).isEqualTo(1);
        assertThat(parsed.rows())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getSnapshotAt()).isEqualTo(snapshotAt);
                    assertThat(item.getSku()).isEqualTo("PNK001-C-Black-M-WFS");
                    assertThat(item.getParentSku()).isEqualTo("PNK001");
                    assertThat(item.getItemId()).isEqualTo("987654321");
                    assertThat(item.getAvailableUnits()).isEqualByComparingTo(new BigDecimal("35"));
                    assertThat(item.getReservedUnits()).isEqualByComparingTo(new BigDecimal("2"));
                    assertThat(item.getInboundUnits()).isEqualByComparingTo(new BigDecimal("8"));
                    assertThat(item.getForecastedDaysSupply()).isEqualByComparingTo(new BigDecimal("14"));
                    assertThat(item.getSuggestedUnits()).isEqualByComparingTo(new BigDecimal("25"));
                    assertThat(item.getRawPayload()).contains("\"Available to sell\":\"35\"");
                });
    }
}
