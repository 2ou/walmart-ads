package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 解析“物流信息登记”表，供货件编号关联渠道、货代和历史签收时效。 */
@Component
public class ShipmentLogisticsWorkbookParser {

    private final JsonPayloadWriter json;

    public ShipmentLogisticsWorkbookParser(JsonPayloadWriter json) {
        this.json = json;
    }

    public ParsedRows<ShipmentLogisticsRecord> parse(Path path, ImportFile importFile) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = ShipmentWorkbookParser.findHeaderRow(sheet, "货件号", "渠道", "货代");
            ShipmentWorkbookParser.Headers headers = ShipmentWorkbookParser.headers(headerRow);
            int shipmentNo = ShipmentWorkbookParser.require(headers, "货件号");
            int forwarder = ShipmentWorkbookParser.require(headers, "货代");
            int channel = ShipmentWorkbookParser.require(headers, "渠道");
            Integer departure = ShipmentWorkbookParser.resolve(headers, "启航&启运日期");
            Integer signed = ShipmentWorkbookParser.resolve(headers, "签收日期");
            Integer signedLeadDays = ShipmentWorkbookParser.resolve(headers, "签收时效");

            int sourceRows = 0;
            List<ShipmentLogisticsRecord> records = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                sourceRows++;
                String number = ShipmentWorkbookParser.cellText(row, shipmentNo);
                if (number == null) {
                    continue;
                }
                LocalDate departureDate = departure == null ? null : ImportValues.date(row.getCell(departure));
                LocalDate signedDate = signed == null ? null : ImportValues.date(row.getCell(signed));
                BigDecimal leadDays = signedLeadDays == null
                        ? BigDecimal.ZERO
                        : ImportValues.decimal(ShipmentWorkbookParser.cellText(row, signedLeadDays));
                if (leadDays.signum() <= 0 && departureDate != null && signedDate != null) {
                    leadDays = BigDecimal.valueOf(ChronoUnit.DAYS.between(departureDate, signedDate));
                }
                records.add(new ShipmentLogisticsRecord(
                        importFile,
                        number,
                        ShipmentWorkbookParser.cellText(row, forwarder),
                        ShipmentWorkbookParser.cellText(row, channel),
                        departureDate,
                        signedDate,
                        leadDays.signum() > 0 ? leadDays : null,
                        json.write(ShipmentWorkbookParser.raw(headers, row))
                ));
            }
            return new ParsedRows<>(sourceRows, records);
        }
    }
}
