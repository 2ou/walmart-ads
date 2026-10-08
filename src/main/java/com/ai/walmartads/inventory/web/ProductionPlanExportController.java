package com.ai.walmartads.inventory.web;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidation;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataValidations;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 做货规划 → 生产订单导出。
 * 把需求预测工作台「已确认」款要做货的 SKU，按工厂「生产订单导入」模板（11 列）生成 xlsx。
 *
 * <p>以工厂原版模板为**基底**（{@value #TEMPLATE_RESOURCE}）打开后只填数据行 —— 表头的
 * `*` 必填标记、Arial 10 加粗/灰底/居中/细边框、列宽 16.72 以及 G/H/I 三列的下拉枚举
 * 全部原样保留，避免手工复刻样式与工厂模板产生漂移。
 *
 * <p>列：款号* / SKC* / SKU* / 面料条数* / 下单数* / 仓库* / 订单类型* / 销售渠道* / 是否开票* / 备注 / 订单组*
 * <ul>
 *   <li>下单数、面料条数 由前端传入（最终数量 finalQuantity 已按比例拆分到各 SKU；面料条数=裁床数 cuttingYield）</li>
 *   <li>仓库 / 订单类型 / 销售渠道 / 是否开票 为本工厂模板固定默认值（取值均落在模板下拉枚举内）</li>
 *   <li>订单组：按出现的不同 SKC（颜色）顺序分组，每组最多 6 个 SKC</li>
 *   <li>备注 留空</li>
 *   <li>下拉验证区：模板预置第 2~101 行；实际数据行更多时自动扩展到实际行数</li>
 *   <li>文件名：生产订单导入_&lt;时间戳&gt;.xlsx</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/production-plan")
public class ProductionPlanExportController {

    /**
     * 工厂「生产订单导入」模板基底（11 列，含表头样式与 G/H/I 三列下拉枚举）。
     * 二进制原文件，随 jar 打包；导出时只读打开、绝不回写。
     */
    private static final String TEMPLATE_RESOURCE = "templates/production-order-import-template.xlsx";

    /** 模板表头真源（去掉 `*` 后的列名，顺序即列序），用于校验模板未被误改。 */
    private static final String[] EXPECTED_HEADERS = {
            "款号", "SKC", "SKU", "面料条数", "下单数",
            "仓库", "订单类型", "销售渠道", "是否开票", "备注", "订单组"
    };

    /** 数据起始行索引（0 基）：第 0 行是表头，数据从第 2 行起。 */
    private static final int DATA_FIRST_ROW_INDEX = 1;

    /** 模板预置的下拉验证区上界（1 基：第 2~101 行）。实际行数超出时按实际行数扩展。 */
    private static final int TEMPLATE_VALIDATION_LAST_ROW = 101;

    // 工厂订单模板固定默认值（PNK 当前工厂配置；如需多工厂可改为配置项）
    private static final String WAREHOUSE = "尚依库-全伟达-斐凡仓库-沃尔玛";
    private static final String ORDER_TYPE = "正常";
    private static final String SALES_CHANNEL = "沃尔玛";
    private static final String INVOICE = "是";
    private static final int SKC_PER_GROUP = 6;

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ProductionPlanExportRequest request) {
        List<ProductionPlanExportRequest.Row> rows =
                request == null || request.rows == null ? List.of() : request.rows;
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body("没有可导出的做货行".getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Integer> skcGroup = groupBySkc(rows);

        byte[] file;
        try (InputStream template = new ClassPathResource(TEMPLATE_RESOURCE).getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(template)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            verifyTemplateHeader(sheet);

            int r = DATA_FIRST_ROW_INDEX;
            for (ProductionPlanExportRequest.Row row : rows) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(nullToEmpty(row.styleNo));
                xr.createCell(1).setCellValue(nullToEmpty(row.skc));
                xr.createCell(2).setCellValue(nullToEmpty(row.sku));
                xr.createCell(3).setCellValue(row.fabricStrips == null ? 0 : row.fabricStrips);
                xr.createCell(4).setCellValue(row.orderQty == null ? 0 : row.orderQty);
                xr.createCell(5).setCellValue(WAREHOUSE);
                xr.createCell(6).setCellValue(ORDER_TYPE);
                xr.createCell(7).setCellValue(SALES_CHANNEL);
                xr.createCell(8).setCellValue(INVOICE);
                xr.createCell(9).setCellValue(""); // 备注：留空
                xr.createCell(10).setCellValue(skcGroup.getOrDefault(row.skc == null ? "" : row.skc, 1));
            }
            resizeValidationRegions(sheet, rows.size());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            file = out.toByteArray();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("导出失败：" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }

        String fileName = request.fileName;
        if (fileName == null || fileName.isBlank()) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            fileName = "生产订单导入_" + stamp + ".xlsx";
        }
        // RFC 5987：legacy filename= 仅放 ASCII 兜底（Tomcat 会丢弃非 ASCII），
        // 真实中文名走 filename*=UTF-8'' 编码，浏览器以此为准下载文件名。
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"production_order_export.xlsx\"; filename*=UTF-8''" + encoded;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.length)
                .body(file);
    }

    /** 订单组：按出现的不同 SKC（颜色）顺序分组，每组最多 {@value #SKC_PER_GROUP} 个。 */
    private static Map<String, Integer> groupBySkc(List<ProductionPlanExportRequest.Row> rows) {
        Map<String, Integer> skcGroup = new LinkedHashMap<>();
        int groupSeq = 0;
        for (ProductionPlanExportRequest.Row row : rows) {
            String skc = row.skc == null ? "" : row.skc;
            if (!skcGroup.containsKey(skc)) {
                groupSeq++;
                skcGroup.put(skc, (groupSeq - 1) / SKC_PER_GROUP + 1);
            }
        }
        return skcGroup;
    }

    /**
     * 把模板预置的下拉验证区（G/H/I 列第 2~101 行）改写为「不短于模板原区间、且不短于实际数据行数」。
     * 只动 sqref，其余验证属性（allowBlank / showDropDown / 枚举值）保持模板原样。
     */
    private static void resizeValidationRegions(XSSFSheet sheet, int dataRowCount) {
        CTWorksheet ctSheet = sheet.getCTWorksheet();
        CTDataValidations validations = ctSheet.getDataValidations();
        if (validations == null) {
            return;
        }
        int lastRowIndex = Math.max(DATA_FIRST_ROW_INDEX + dataRowCount - 1, TEMPLATE_VALIDATION_LAST_ROW - 1);
        for (CTDataValidation validation : validations.getDataValidationList()) {
            List<String> resized = new ArrayList<>();
            for (Object ref : validation.getSqref()) {
                CellRangeAddress range = CellRangeAddress.valueOf(String.valueOf(ref));
                String start = new CellReference(range.getFirstRow(), range.getFirstColumn()).formatAsString();
                String end = new CellReference(lastRowIndex, range.getLastColumn()).formatAsString();
                resized.add(start + ":" + end);
            }
            validation.setSqref(resized);
        }
    }

    /** 模板防呆：表头（忽略 `*`）必须与 {@link #EXPECTED_HEADERS} 逐列一致，否则模板被误改，宁可报错也不导出错版。 */
    private static void verifyTemplateHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new IllegalStateException("工厂模板缺少表头行");
        }
        DataFormatter formatter = new DataFormatter();
        for (int i = 0; i < EXPECTED_HEADERS.length; i++) {
            String actual = formatter.formatCellValue(header.getCell(i)).replace("*", "").trim();
            if (!EXPECTED_HEADERS[i].equals(actual)) {
                throw new IllegalStateException(
                        "工厂模板第 " + (i + 1) + " 列应为「" + EXPECTED_HEADERS[i] + "」，实际「" + actual + "」");
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 请求体：rows 为要做货的 SKU 行（已在前端按「已确认」过滤并拆分最终数量）。 */
    public static class ProductionPlanExportRequest {
        public List<Row> rows;
        public String fileName;

        public static class Row {
            public String styleNo;
            public String skc;
            public String sku;
            public Integer fabricStrips;
            public Integer orderQty;
        }
    }
}
