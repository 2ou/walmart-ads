package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentWorkbookParserTest {

    @TempDir
    Path temp;

    @Test
    void keepsOnlyTransitStatusesCarriesShipmentFieldsAndClampsNegativeDifference() throws Exception {
        Path workbookPath = temp.resolve("货件列表.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("sheet1");
            var header = sheet.createRow(0);
            String[] names = {"货件单号", "货件本地状态", "创建时间", "SKU", "申收差异"};
            for (int index = 0; index < names.length; index++) {
                header.createCell(index).setCellValue(names[index]);
            }
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("SHP-1");
            first.createCell(1).setCellValue("已发货");
            first.createCell(2).setCellValue("2026-07-25 10:55");
            first.createCell(3).setCellValue("PNK100-A-WFS");
            first.createCell(4).setCellValue(-4);
            var inherited = sheet.createRow(2);
            inherited.createCell(3).setCellValue("PNK100-B-WFS");
            inherited.createCell(4).setCellValue(6);
            var completed = sheet.createRow(3);
            completed.createCell(0).setCellValue("SHP-2");
            completed.createCell(1).setCellValue("已完成");
            completed.createCell(2).setCellValue("2026-07-26 10:55");
            completed.createCell(3).setCellValue("PNK200-A-WFS");
            completed.createCell(4).setCellValue(9);
            var declared = sheet.createRow(4);
            declared.createCell(0).setCellValue("SHP-3");
            declared.createCell(1).setCellValue("已申报");
            declared.createCell(2).setCellValue("2026-07-27 10:55");
            declared.createCell(3).setCellValue("PNK300-A-WFS");
            declared.createCell(4).setCellValue(3);
            try (var output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        ParsedRows<?> parsed = new ShipmentWorkbookParser(new JsonPayloadWriter(new ObjectMapper()))
                .parse(workbookPath, importFile());

        assertThat(parsed.sourceRowCount()).isEqualTo(4);
        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.rows().get(0))
                .extracting("shipmentNo", "localStatus", "receiptDifference")
                .containsExactly("SHP-1", "已发货", BigDecimal.ZERO);
        assertThat(parsed.rows().get(1))
                .extracting("shipmentNo", "sku", "receiptDifference")
                .containsExactly("SHP-1", "PNK100-B-WFS", BigDecimal.valueOf(6));
        assertThat(parsed.rows().get(2))
                .extracting("shipmentNo", "localStatus")
                .containsExactly("SHP-3", "已申报");
    }

    private ImportFile importFile() {
        return new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.SHIPMENT,
                null,
                "货件列表.xlsx",
                temp.resolve("货件列表.xlsx").toString(),
                "0".repeat(64),
                0,
                java.time.LocalDateTime.now(),
                null,
                null
        );
    }
}
