package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ProductRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Component
public class ProductWorkbookParser {

    private final JsonPayloadWriter json;

    public ProductWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<ProductRecord> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input, importFile);
        }
    }

    public ParsedRows<ProductRecord> parse(byte[] content, ImportFile importFile) throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            return parse(input, importFile);
        }
    }

    private ParsedRows<ProductRecord> parse(InputStream input, ImportFile importFile) throws IOException {
        List<ProductRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = findProductSheet(workbook);
            Header header = findTechnicalHeader(sheet);
            for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String sku = ImportValues.exactText(row.getCell(header.skuColumn()));
                if (sku == null || !sku.trim().toUpperCase().startsWith("PNK")) {
                    continue;
                }
                Map<String, Object> raw = new LinkedHashMap<>();
                for (int column = 0; column < header.names().size(); column++) {
                    String name = header.names().get(column);
                    if (name != null && "price".equalsIgnoreCase(name.trim())) {
                        continue;
                    }
                    String value = column == header.skuColumn()
                            ? sku
                            : ImportValues.text(row.getCell(column));
                    ImportValues.addPreservingDuplicates(raw, name, value);
                }
                ProductRecord record = new ProductRecord(
                        importFile,
                        importFile.getSourceModifiedAt(),
                        sku,
                        ImportValues.parentSku(sku),
                        ImportValues.first(raw, "specProductType"),
                        ImportValues.first(raw, "productId"),
                        ImportValues.first(raw, "productIdType"),
                        ImportValues.first(raw, "productName"),
                        ImportValues.first(raw, "brand"),
                        ImportValues.first(raw, "shortDescription"),
                        json.write(ImportValues.list(raw, "keyFeatures")),
                        ImportValues.first(raw, "mainImageUrl"),
                        json.write(ImportValues.list(raw, "productSecondaryImageURL")),
                        ImportValues.first(raw, "color"),
                        ImportValues.first(raw, "colorCategory"),
                        ImportValues.first(raw, "clothingSize"),
                        ImportValues.first(raw, "clothingSizeGroup"),
                        ImportValues.first(raw, "pattern"),
                        ImportValues.first(raw, "materialName"),
                        ImportValues.first(raw, "gender"),
                        ImportValues.first(raw, "variantGroupId"),
                        json.write(raw)
                );
                record.setSourceRowNumber(rowIndex + 1);
                records.add(record);
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static Sheet findProductSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            if (sheet.getSheetName().startsWith("Product Content And Site Exp")) {
                return sheet;
            }
        }
        throw new IllegalArgumentException("商品资料缺少 Product Content And Site Exp 工作表");
    }

    private static Header findTechnicalHeader(Sheet sheet) {
        int maxRows = Math.min(sheet.getLastRowNum(), 15);
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= maxRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            int skuColumn = -1;
            for (int column = 0; column < row.getLastCellNum(); column++) {
                String name = ImportValues.text(row.getCell(column));
                names.add(name);
                if ("sku".equals(name)) {
                    skuColumn = column;
                }
            }
            if (skuColumn >= 0) {
                return new Header(rowIndex, skuColumn, names);
            }
        }
        throw new IllegalArgumentException("商品资料缺少技术字段行 sku");
    }

    private record Header(int rowIndex, int skuColumn, List<String> names) {
    }
}
