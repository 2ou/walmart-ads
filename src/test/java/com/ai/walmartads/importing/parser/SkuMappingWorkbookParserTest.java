package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.SkuMapping;
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

class SkuMappingWorkbookParserTest {

    @TempDir
    Path temp;

    @Test
    void parsesSkuItemIdGtinAndUpcAsIdentifiers() throws Exception {
        Path path = temp.resolve("WFS-upload-template.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            writeRow(sheet.createRow(0), List.of("SKU", "Item ID", "GTIN", "UPC", "Item Name"));
            writeRow(sheet.createRow(1), List.of(
                    "PNK361002-C-Black-M-WFS",
                    "5479331637",
                    "00740379280190",
                    "740379280190",
                    "Black M"
            ));
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.SKU_MAPPING,
                null,
                path.getFileName().toString(),
                path.toString(),
                "0".repeat(64),
                Files.size(path),
                LocalDateTime.now(),
                null,
                LocalDateTime.now()
        );

        ParsedRows<SkuMapping> parsed = new SkuMappingWorkbookParser(
                new JsonPayloadWriter(new ObjectMapper())
        ).parse(path, importFile);

        assertThat(parsed.sourceRowCount()).isEqualTo(1);
        assertThat(parsed.rows())
                .singleElement()
                .satisfies(mapping -> {
                    assertThat(mapping.getSku()).isEqualTo("PNK361002-C-Black-M-WFS");
                    assertThat(mapping.getParentSku()).isEqualTo("PNK361002");
                    assertThat(mapping.getItemId()).isEqualTo("5479331637");
                    assertThat(mapping.getGtin()).isEqualTo("00740379280190");
                    assertThat(mapping.getUpc()).isEqualTo("740379280190");
                });
    }

    private static void writeRow(Row row, List<String> values) {
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column));
        }
    }
}
