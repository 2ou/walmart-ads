package com.ai.walmartads.inventory.web;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidation;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidations;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产订单导出的模板基底行为：表头/样式/下拉来自工厂模板，只追加数据行。
 */
class ProductionPlanExportControllerTest {

    private final ProductionPlanExportController controller = new ProductionPlanExportController();

    @Test
    void exportsOnTopOfFactoryTemplateKeepingStarredHeadersAndDefaultColumns() throws Exception {
        ResponseEntity<byte[]> response = controller.export(request(
                row("PNK366066", "PNK366066-BLK", "PNK366066-BLK-S", 2, 120),
                row("PNK366066", "PNK366066-BLK", "PNK366066-BLK-M", 2, 90)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        try (XSSFWorkbook workbook = open(response)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("生产订单导入");
            // 表头原封来自模板：带 * 必填标记，仅「备注」无标记
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("款号*");
            assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("备注");
            assertThat(sheet.getRow(0).getCell(10).getStringCellValue()).isEqualTo("订单组*");
            // 表头样式随模板保留
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getFontName()).isEqualTo("Arial");
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold()).isTrue();
            // 数据行 + 工厂默认值列
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("PNK366066");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("PNK366066-BLK");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("PNK366066-BLK-S");
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(2d);
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(120d);
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("尚依库-全伟达-斐凡仓库-沃尔玛");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("正常");
            assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("沃尔玛");
            assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("是");
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(1).getCell(10).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(2).getCell(10).getNumericCellValue()).isEqualTo(1d);
        }
    }

    @Test
    void keepsTemplateDropdownEnumsAndDefaultValidationSpanForSmallExports() throws Exception {
        ResponseEntity<byte[]> response = controller.export(request(
                row("PNK366066", "PNK366066-BLK", "PNK366066-BLK-S", 2, 120)));

        try (XSSFWorkbook workbook = open(response)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            // 数据行少于模板预置容量时，验证区保持模板原样（第 2~101 行）
            assertThat(validationSqrefs(sheet))
                    .containsExactlyInAnyOrder("G2:G101", "H2:H101", "I2:I101");
            assertThat(validationListValues(sheet, "G2:G101"))
                    .contains("正常", "加急 提前 7天", "TK订单");
            assertThat(validationListValues(sheet, "H2:H101")).contains("沃尔玛", "亚马逊");
            assertThat(validationListValues(sheet, "I2:I101")).contains("是", "否");
        }
    }

    @Test
    void growsValidationRegionWhenExportExceedsTemplateCapacity() throws Exception {
        List<ProductionPlanExportController.ProductionPlanExportRequest.Row> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(row("PNK366066", "SKC" + (i / 6), "PNK366066-BLK-" + i, 1, 10));
        }

        try (XSSFWorkbook workbook = open(controller.export(request(rows.toArray(
                new ProductionPlanExportController.ProductionPlanExportRequest.Row[0]))))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(validationSqrefs(sheet))
                    .containsExactlyInAnyOrder("G2:G201", "H2:H201", "I2:I201");
        }
    }

    @Test
    void rejectsEmptyExport() {
        ResponseEntity<byte[]> response = controller.export(request());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static XSSFWorkbook open(ResponseEntity<byte[]> response) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(response.getBody()));
    }

    /** 直接读底层 sqref，等价于 Excel 里「数据验证」对话框中显示的区间。 */
    private static List<String> validationSqrefs(XSSFSheet sheet) {
        List<String> refs = new ArrayList<>();
        CTDataValidations validations = sheet.getCTWorksheet().getDataValidations();
        if (validations == null) {
            return refs;
        }
        for (CTDataValidation validation : validations.getDataValidationList()) {
            for (Object ref : validation.getSqref()) {
                refs.add(String.valueOf(ref));
            }
        }
        return refs;
    }

    private static String validationListValues(XSSFSheet sheet, String sqref) {
        CTDataValidations validations = sheet.getCTWorksheet().getDataValidations();
        for (CTDataValidation validation : validations.getDataValidationList()) {
            for (Object ref : validation.getSqref()) {
                if (sqref.equals(String.valueOf(ref))) {
                    return validation.getFormula1();
                }
            }
        }
        throw new AssertionError("未找到验证区 " + sqref);
    }

    private static ProductionPlanExportController.ProductionPlanExportRequest request(
            ProductionPlanExportController.ProductionPlanExportRequest.Row... rows) {
        ProductionPlanExportController.ProductionPlanExportRequest request =
                new ProductionPlanExportController.ProductionPlanExportRequest();
        request.rows = List.of(rows);
        request.fileName = "生产订单导入_test.xlsx";
        return request;
    }

    private static ProductionPlanExportController.ProductionPlanExportRequest.Row row(
            String styleNo, String skc, String sku, Integer fabricStrips, Integer orderQty) {
        ProductionPlanExportController.ProductionPlanExportRequest.Row row =
                new ProductionPlanExportController.ProductionPlanExportRequest.Row();
        row.styleNo = styleNo;
        row.skc = skc;
        row.sku = sku;
        row.fabricStrips = fabricStrips;
        row.orderQty = orderQty;
        return row;
    }
}
