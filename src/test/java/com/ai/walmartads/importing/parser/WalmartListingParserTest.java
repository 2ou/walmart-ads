package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalmartListingParserTest {

    @TempDir
    Path temp;

    @Test
    void readsStrikethroughPriceByHeaderName() throws Exception {
        Path path = temp.resolve("walmart-listing.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("sheet1");
            writeRow(sheet.createRow(0), List.of("商品ID", "SKU", "价格", "划线价", "upc", "gtin", "状态", "店铺", "品名"));
            writeRow(sheet.createRow(1), List.of(
                    "20079573828", "PNK366066-C-Dark Gray-2XL-WFS", "$12.98", "$11.98",
                    "789044682709", "00789044682709", "PUBLISHED", "PINKSIR", "PNK366066"
            ));
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }

        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.WALMART_LISTING,
                null,
                path.getFileName().toString(),
                path.toString(),
                "0".repeat(64),
                Files.size(path),
                LocalDateTime.now(),
                null,
                null
        );

        ParsedRows<WalmartListingRow> parsed = new WalmartListingParser().parse(path, importFile);

        assertThat(parsed.sourceRowCount()).isEqualTo(1);
        assertThat(parsed.rows()).singleElement().satisfies(row -> {
            assertThat(row.sku()).isEqualTo("PNK366066-C-Dark Gray-2XL-WFS");
            assertThat(row.price()).isEqualByComparingTo(new BigDecimal("12.98"));
            assertThat(row.strikethroughPrice()).isEqualByComparingTo(new BigDecimal("11.98"));
        });
    }

    private static void writeRow(Row row, List<String> values) {
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column));
        }
    }
}
