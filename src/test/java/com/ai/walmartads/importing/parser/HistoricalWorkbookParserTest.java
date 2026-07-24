package com.ai.walmartads.importing.parser;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalWorkbookParserTest {

    @TempDir
    Path temp;

    @Test
    void importsOnlyRowsBeforeConfiguredCutoff() throws Exception {
        Path path = temp.resolve("history.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            writeOrders(workbook);
            writeAds(workbook);
            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }

        WalmartAdsProperties properties = new WalmartAdsProperties(
                temp,
                temp.resolve("archive"),
                path,
                LocalDate.of(2026, 7, 21)
        );
        HistoricalWorkbookParser parser = new HistoricalWorkbookParser(
                new JsonPayloadWriter(new ObjectMapper()),
                properties
        );
        ImportFile importFile = new ImportFile(
                new ImportBatch(temp.toString()),
                ImportFileType.HISTORY,
                AdReportKind.ITEM_PERFORMANCE,
                path.getFileName().toString(),
                path.toString(),
                "0".repeat(64),
                Files.size(path),
                LocalDateTime.now(),
                null,
                null
        );

        HistoricalWorkbookRows parsed = parser.parse(path, importFile);

        assertThat(parsed.sourceRowCount()).isEqualTo(4);
        assertThat(parsed.orders()).hasSize(1);
        assertThat(parsed.ads())
                .singleElement()
                .extracting(item -> item.getAdType())
                .isEqualTo(ImportFileType.AD_AUTO);
    }

    private static void writeOrders(Workbook workbook) {
        Sheet sheet = workbook.createSheet("当天销量");
        List<String> headers = List.of(
                "PO#", "Order#", "Order Date", "Line#", "UPC", "Status",
                "Item Description", "Shipping Method", "Fulfillment Entity",
                "Qty", "SKU", "Item Cost", "Shipping Cost", "Tax"
        );
        writeRow(sheet.createRow(0), headers);
        writeRow(sheet.createRow(1), List.of(
                "PO-1", "ORDER-1", "2026-07-20", "1", "123", "Delivered",
                "Product", "STANDARD", "WFS", "2", "PNK001-C-Black-M-WFS",
                "New", "12.50", "0"
        ));
        writeRow(sheet.createRow(2), List.of(
                "PO-2", "ORDER-2", "2026-07-21", "1", "124", "Delivered",
                "Product", "STANDARD", "WFS", "1", "PNK002-C-Black-M-WFS",
                "New", "15.00", "0"
        ));
    }

    private static void writeAds(Workbook workbook) {
        Sheet sheet = workbook.createSheet("当天广告数据");
        String[] headers = new String[37];
        headers[0] = "广告活动名称";
        headers[1] = "广告活动ID";
        headers[2] = "广告组名称";
        headers[3] = "广告组ID";
        headers[4] = "产品ID";
        headers[5] = "产品名字";
        headers[6] = "出价";
        headers[7] = "平均每次点击费用";
        headers[8] = "展示次数";
        headers[9] = "点击次数";
        headers[10] = "点击率";
        headers[11] = "广告支出";
        headers[13] = "订单";
        headers[14] = "转化率（基于订单）";
        headers[15] = "归因销售总额";
        headers[16] = "Advertised SKU Sales";
        headers[17] = "Other SKU Sales";
        headers[18] = "Units Sold";
        headers[30] = "投资回报率RoAS";
        headers[32] = "日期";
        headers[33] = "父SKU";
        headers[34] = "父SKU2";
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.length; column++) {
            if (headers[column] != null) {
                header.createCell(column).setCellValue(headers[column]);
            }
        }
        writeAdRow(sheet.createRow(1), "2026-07-20");
        writeAdRow(sheet.createRow(2), "2026-07-21");
    }

    private static void writeAdRow(Row row, String date) {
        row.createCell(0).setCellValue("PNK001-ZDGG");
        row.createCell(1).setCellValue("100");
        row.createCell(2).setCellValue("PNK001-ZDGG");
        row.createCell(3).setCellValue("200");
        row.createCell(4).setCellValue("300");
        row.createCell(5).setCellValue("Product");
        row.createCell(6).setCellValue(0.3);
        row.createCell(7).setCellValue(0.2);
        row.createCell(8).setCellValue(100);
        row.createCell(9).setCellValue(5);
        row.createCell(10).setCellValue(0.05);
        row.createCell(11).setCellValue(1);
        row.createCell(13).setCellValue(1);
        row.createCell(14).setCellValue(0.2);
        row.createCell(15).setCellValue(12.5);
        row.createCell(16).setCellValue(12.5);
        row.createCell(17).setCellValue(0);
        row.createCell(18).setCellValue(1);
        row.createCell(30).setCellValue(12.5);
        row.createCell(32).setCellValue(date);
        row.createCell(33).setCellValue("PNK001");
        row.createCell(34).setCellValue("PNK001");
    }

    private static void writeRow(Row row, List<String> values) {
        for (int column = 0; column < values.size(); column++) {
            row.createCell(column).setCellValue(values.get(column));
        }
    }
}
