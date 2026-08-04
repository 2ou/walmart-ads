package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.application.DeliveryFeeCalculator;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ItemReportRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ItemReportCsvParser {

    private final JsonPayloadWriter json;
    private final DeliveryFeeCalculator calculator;

    public ItemReportCsvParser(JsonPayloadWriter json, DeliveryFeeCalculator calculator) {
        this.json = json;
        this.calculator = calculator;
    }

    public ParsedRows<ItemReportRecord> parse(Path path, ImportFile importFile) throws IOException {
        List<ItemReportRecord> records = new ArrayList<>();
        int sourceRows = 0;
        try (Reader reader = bomSafeReader(path);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                sourceRows++;
                Map<String, String> values = normalized(record.toMap());
                String sku = ImportValues.text(values, "SKU");
                if (sku == null || !sku.toUpperCase().startsWith("PNK")) {
                    continue;
                }
                Map<String, String> raw = nonEmpty(values);
                BigDecimal price = ImportValues.decimal(values.get("Price"));
                BigDecimal shippingWeightLb = ImportValues.decimal(values.get("Shipping Weight"));
                String shippingWeightUnit = stripQuotes(ImportValues.text(values, "Shipping Weight Unit"));
                String productCategory = ImportValues.text(values, "Product Category");
                DeliveryFeeCalculator.DeliveryFee fee = calculator.compute(shippingWeightLb, price, productCategory);
                LocalDate updatedDate = ImportValues.date(values.get("Item Last Updated"));
                LocalDateTime sourceUpdatedAt = updatedDate == null
                        ? importFile.getSourceModifiedAt()
                        : updatedDate.atTime(LocalTime.NOON);
                records.add(new ItemReportRecord(
                        importFile,
                        sourceUpdatedAt,
                        sku,
                        ImportValues.parentSku(sku),
                        ImportValues.text(values, "Item ID"),
                        ImportValues.text(values, "UPC"),
                        ImportValues.text(values, "WPID"),
                        ImportValues.text(values, "GTIN"),
                        ImportValues.text(values, "Product Name"),
                        ImportValues.text(values, "Brand"),
                        productCategory,
                        ImportValues.text(values, "Lifecycle Status"),
                        ImportValues.text(values, "Fulfillment Type"),
                        price,
                        ImportValues.text(values, "Currency"),
                        ImportValues.text(values, "Price Type"),
                        shippingWeightLb,
                        shippingWeightUnit,
                        fee.shippingWeightKg(),
                        fee.billingWeightLb(),
                        fee.baseFee(),
                        fee.apparelSurcharge(),
                        fee.lowPriceSurcharge(),
                        fee.deliveryFee(),
                        json.write(raw)
                ));
            }
        }
        return new ParsedRows<>(sourceRows, records);
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static Reader bomSafeReader(Path path) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }

    private static Map<String, String> normalized(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key == null ? null : key.replace("\uFEFF", "").trim(), value));
        return result;
    }

    private static Map<String, String> nonEmpty(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String cleaned = ImportValues.blankToNull(value);
            if (cleaned != null) {
                result.put(key, cleaned);
            }
        });
        return result;
    }
}
