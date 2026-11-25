package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.workspace.WorkspaceOwnershipService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductWorkbookParserTest {

    @TempDir
    Path temp;

    @Test
    void ignoresUploadPriceInStructuredAndRawProductData() throws Exception {
        Path path = temp.resolve("product-upload.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Product Content And Site Exp");
            writeRow(sheet.createRow(0), List.of("sku", "productName", "price", "color"));
            writeRow(sheet.createRow(1), List.of("DEMO100-Blue-M-WFS", "Blue listing", "19.99", "Blue"));
            writeRow(sheet.createRow(2), List.of("OTHER101-Red-M-WFS", "Foreign listing", "12.99", "Red"));
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.PRODUCT,
                null,
                path.getFileName().toString(),
                path.toString(),
                "0".repeat(64),
                Files.size(path),
                LocalDateTime.now(),
                null,
                null
        );

        ParsedRows<ProductRecord> parsed = parserFor("DEMO").parse(Files.readAllBytes(path), importFile);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst().getRawPayload())
                .contains("\"productName\":\"Blue listing\"", "\"color\":\"Blue\"")
                .doesNotContain("\"price\"");
    }

    @Test
    void preservesExactSkuTextAndWorkbookRowNumber() throws Exception {
        Path path = temp.resolve("exact-sku-upload.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Product Content And Site Exp");
            writeRow(sheet.createRow(0), List.of("sku", "productName"));
            writeRow(sheet.createRow(1), List.of(" DEMO100-Blue-M ", "Exact identifier"));
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        ImportFile importFile = new ImportFile(new ImportBatch(temp.toString()), ImportFileType.PRODUCT, null,
                path.getFileName().toString(), path.toString(), "0".repeat(64), Files.size(path),
                LocalDateTime.now(), null, null);

        ProductRecord record = parserFor("DEMO").parse(Files.readAllBytes(path), importFile).rows().getFirst();

        assertThat(record.getSku()).isEqualTo(" DEMO100-Blue-M ");
        assertThat(record.getSourceRowNumber()).isEqualTo(2);
    }

    private static ProductWorkbookParser parserFor(String prefix) {
        WorkspaceOwnershipService ownership = mock(WorkspaceOwnershipService.class);
        when(ownership.currentPrefixes()).thenReturn(List.of(prefix));
        return new ProductWorkbookParser(new JsonPayloadWriter(new ObjectMapper()), ownership);
    }

    private static void writeRow(Row row, List<String> values) {
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column));
        }
    }
}
