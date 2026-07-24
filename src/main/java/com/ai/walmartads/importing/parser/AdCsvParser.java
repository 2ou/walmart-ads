package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AdCsvParser {

    private final JsonPayloadWriter json;
    private final SkuResolver skuResolver;

    public AdCsvParser(JsonPayloadWriter json, SkuResolver skuResolver) {
        this.json = json;
        this.skuResolver = skuResolver;
    }

    public ParsedRows<AdPerformance> parse(Path path, ImportFile importFile) throws IOException {
        LocalDate reportDate = importFile.getReportDate();
        if (reportDate == null) {
            throw new IllegalArgumentException("广告文件缺少日期目录：" + path);
        }
        List<AdPerformance> rows = new ArrayList<>();
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
                String campaignName = ImportValues.text(values, "Campaign Name");
                if (campaignName == null) {
                    continue;
                }
                String itemId = ImportValues.text(values, "Item Id");
                String campaignType = ImportValues.text(values, "Campaign Type");
                SkuResolver.ResolvedSku resolved = skuResolver.resolve(itemId, ImportValues.text(values, "SKU Id"));
                BigDecimal spend = ImportValues.decimal(values.get("Ad Spend"));
                BigDecimal sales = ImportValues.decimal(values.get("Total Attributed Sales"));
                BigDecimal roas = ImportValues.decimal(values.get("RoAS"));
                if (roas.signum() == 0 && spend.signum() > 0) {
                    roas = sales.divide(spend, 8, java.math.RoundingMode.HALF_UP);
                }
                rows.add(new AdPerformance(
                        importFile,
                        reportDate,
                        adType(campaignType, importFile),
                        campaignName,
                        ImportValues.text(values, "Campaign Id"),
                        campaignType,
                        ImportValues.text(values, "Current Bidding Strategy"),
                        ImportValues.decimal(values.get("Current Target ROAS")),
                        ImportValues.text(values, "Ad Group Name"),
                        ImportValues.text(values, "Ad Group Id"),
                        itemId,
                        resolved.sku(),
                        resolved.parentSku(),
                        ImportValues.text(values, "Item Name"),
                        ImportValues.text(values, "Searched Keyword"),
                        ImportValues.text(values, "Bidded Keyword"),
                        ImportValues.text(values, "Match Type"),
                        ImportValues.decimal(values.get("Bid")),
                        ImportValues.decimal(values.get("Average CPC")),
                        ImportValues.whole(values.get("Impressions")),
                        ImportValues.whole(values.get("Clicks")),
                        ImportValues.decimal(values.get("CTR")),
                        spend,
                        ImportValues.decimal(values.get("Orders")),
                        ImportValues.decimal(values.get("Conversion Rate")),
                        sales,
                        ImportValues.decimal(values.get("Advertised SKU Sales")),
                        ImportValues.decimal(values.get("Other SKU Sales")),
                        ImportValues.decimal(values.get("Units Sold")),
                        roas,
                        ImportValues.decimal(values.get("Omni ROAS")),
                        json.write(nonEmpty(values))
                ));
            }
        }
        return new ParsedRows<>(sourceRows, rows);
    }

    private static ImportFileType adType(String campaignType, ImportFile importFile) {
        if (campaignType != null) {
            String normalized = campaignType.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("(auto)") || normalized.contains("automatic")) {
                return ImportFileType.AD_AUTO;
            }
            if (normalized.contains("(manual)") || normalized.contains("keyword bidded")) {
                return ImportFileType.AD_MANUAL;
            }
        }
        if (importFile.getFileType() == ImportFileType.AD_AUTO
                || importFile.getFileType() == ImportFileType.AD_MANUAL) {
            return importFile.getFileType();
        }
        throw new IllegalArgumentException(
                "无法根据 Campaign Type 判断自动/手动广告："
                        + importFile.getFileName() + "，Campaign Type=" + campaignType
        );
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
        source.forEach((key, value) -> result.put(key.replace("\uFEFF", "").trim(), value));
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
