package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReturnOrderWorkbookParserTest {

    private static final Path ROOT = Path.of("D:/PNK/17DATA/source/Return_Orders");

    @Test
    void parsesCustomerReturnsWorkbook() throws Exception {
        assumeTrue(Files.isDirectory(ROOT), "本机退款报表目录不存在，跳过真实文件测试");
        ReturnOrderWorkbookParser parser = new ReturnOrderWorkbookParser(
                new JsonPayloadWriter(new ObjectMapper())
        );

        int sourceRows = 0;
        int importedRows = 0;
        var workbooks = Files.list(ROOT)
                .filter(item -> item.getFileName().toString()
                        .matches("(?i)^Customer returns-\\d{6}\\.xlsx$"))
                .sorted()
                .toList();
        assumeTrue(!workbooks.isEmpty(), "未找到 Customer returns-*.xlsx，跳过");
        for (Path path : workbooks) {
            String month = path.getFileName().toString().replaceAll(".*(\\d{4})(\\d{2}).*", "$1-$2");
            LocalDate reportMonth = LocalDate.parse(month + "-01");
            ImportFile file = new ImportFile(
                    new ImportBatch(ROOT.toString()),
                    ImportFileType.RETURN_ORDER,
                    null,
                    path.getFileName().toString(),
                    path.toString(),
                    "0".repeat(64),
                    Files.size(path),
                    ImportValues.fileTime(Files.getLastModifiedTime(path).toInstant()),
                    reportMonth,
                    null
            );
            ParsedRows<ReturnOrderRecord> parsed = parser.parse(path, file);
            assertThat(parsed.sourceRowCount()).as(path.toString()).isPositive();
            // NEW 模板应被 1:1 解析，无因缺字段被跳过/抛错
            assertThat(parsed.rows()).as(path.toString()).hasSize(parsed.sourceRowCount());
            assertThat(parsed.rows()).allSatisfy(row -> assertThat(row.getReportMonth())
                    .isEqualTo(row.getRequestAt().toLocalDate().withDayOfMonth(1)));
            sourceRows += parsed.sourceRowCount();
            importedRows += parsed.rows().size();
        }

        assertThat(sourceRows).isPositive();
        assertThat(importedRows).isEqualTo(sourceRows);
    }
}
