package com.ai.walmartads.wfs;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WfsReportParserTest {

    private final WfsReportParser parser = new WfsReportParser();

    @Test
    void parsesSettlementCsvAndKeepsBlankTransactionDate() throws Exception {
        String csv = """
                PINKSIR
                ReportingPeriod_StartDate,ReportingPeriod_EndDate
                2026-07-01,2026-10-01
                Walmart.com Order #,Walmart.com Order Line #,Transaction Type,Reason Code,WFSReferenceID,Transaction Date/Time,Qty,Partner GTIN,Net Payable
                =\"200014234\",1,FulfillmentFee,,REF-1,2026-09-29 16:46:01,1,=\"00789123456789\",4.1200
                ,,StorageFee,,,,,,38.20
                """;

        WfsReportParser.ParsedReport report = parser.parse(
                WfsReportType.SETTLEMENT, "settlement.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.sourceStore()).isEqualTo("PINKSIR");
        assertThat(report.periodStart()).hasToString("2026-07-01");
        assertThat(report.rows()).hasSize(2);
        assertThat(report.errors()).isEmpty();
        WfsReportParser.SettlementRow first = (WfsReportParser.SettlementRow) report.rows().getFirst();
        WfsReportParser.SettlementRow second = (WfsReportParser.SettlementRow) report.rows().get(1);
        assertThat(first.orderId()).isEqualTo("200014234");
        assertThat(first.partnerGtin()).isEqualTo("00789123456789");
        assertThat(first.netPayable()).isEqualByComparingTo("4.1200");
        assertThat(second.transactionAt()).isNull();
    }

    @Test
    void parsesStorageCsvSummaryAndSkipsDescriptionRow() throws Exception {
        String csv = """
                PINKSIR
                https://sellerhelp.walmart.com/storage

                Report start date,Report end date,Total storage fees,Standard storage fees,Peak storage fees,Long-term storage fees (366-450 days),Long-term storage fees (450+ days)
                2026-09-13,2026-10-12,10.25,8.00,0,2.00,.25

                GTIN,SKU,Item ID,Item Name,Length (in),Width (in),Height (in),Weight (lb),Standard: daily storage fee per unit,Peak: daily storage fee per unit,Long-term (366-450): daily storage fee per unit,Long-term (450+): daily storage fee per unit,Standard average daily units available,Peak average daily units available,Long-term (366-450): average daily units available,Long-term (450+): average daily units available,Days in report period,Final storage fee
                14-digit Global Trade Item Number,Partner SKU,Item ID,Item name,,,,,,,,,,,,,,To find the final storage fee
                00012345678901,PNK100-A-Blue-S-WFS,12345,Blue top,12,8,1,0.5,0.01,0,0.02,0.03,4.5,0,1,0.5,30,10.24999
                """;

        WfsReportParser.ParsedReport report = parser.parse(
                WfsReportType.STORAGE, "storage.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rows()).hasSize(1);
        assertThat(report.errors()).isEmpty();
        assertThat(report.summary().get("Total storage fees")).isEqualTo(new BigDecimal("10.25"));
        WfsReportParser.StorageRow row = (WfsReportParser.StorageRow) report.rows().getFirst();
        assertThat(row.sku()).isEqualTo("PNK100-A-Blue-S-WFS");
        assertThat(row.longTermOver450AverageUnits()).isEqualByComparingTo("0.5");
        assertThat(row.finalStorageFee()).isEqualByComparingTo("10.24999");
    }

    @Test
    void readsXlsxWithSameStructuralRules() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Settlement");
            sheet.createRow(0).createCell(0).setCellValue("PINKSIR");
            var periodHeader = sheet.createRow(1);
            periodHeader.createCell(0).setCellValue("ReportingPeriod_StartDate");
            periodHeader.createCell(1).setCellValue("ReportingPeriod_EndDate");
            var period = sheet.createRow(2);
            period.createCell(0).setCellValue("2026-07-01");
            period.createCell(1).setCellValue("2026-10-01");
            String[] headers = {"Walmart.com Order #", "Transaction Type", "WFSReferenceID", "Partner GTIN", "Net Payable"};
            var header = sheet.createRow(3);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(4);
            row.createCell(0).setCellValue("ORDER-1");
            row.createCell(1).setCellValue("Charge");
            row.createCell(2).setCellValue("REF-XLSX");
            row.createCell(3).setCellValue("00123456789012");
            row.createCell(4).setCellValue(1.25);
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        WfsReportParser.ParsedReport report = parser.parse(WfsReportType.SETTLEMENT, "settlement.xlsx", workbookBytes);

        assertThat(report.rows()).hasSize(1);
        assertThat(((WfsReportParser.SettlementRow) report.rows().getFirst()).wfsReferenceId()).isEqualTo("REF-XLSX");
    }

    @Test
    void reportsBadRowsWithoutRejectingValidRows() throws Exception {
        String csv = """
                PINKSIR
                Report start date,Report end date,Total storage fees
                2026-09-01,2026-09-30,12
                GTIN,SKU,Item ID,Item Name,Days in report period,Final storage fee
                001,PNK1-A,1,Valid,30,12
                002,PNK2-A,2,Bad,30,not-money
                """;

        WfsReportParser.ParsedReport report = parser.parse(
                WfsReportType.STORAGE, "storage.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(report.rows()).hasSize(1);
        assertThat(report.errors()).singleElement().satisfies(error -> {
            assertThat(error.rowNumber()).isEqualTo(6);
            assertThat(error.message()).contains("Final storage fee");
        });
    }

    @Test
    void rejectsWrongReportStructure() {
        assertThatThrownBy(() -> parser.parse(WfsReportType.SETTLEMENT, "storage.csv",
                "GTIN,SKU\n1,A".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Settlement");
    }
}
