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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReturnOrderWorkbookParserTest {

    private static final Path ROOT = Path.of("D:/PNK/17DATA/source/Return_Orders");

    @Test
    void parsesAllRowsEvenWhenWorkbookDimensionSaysA1() throws Exception {
        assumeTrue(Files.isDirectory(ROOT), "本机退款报表目录不存在，跳过真实文件测试");
        SkuResolver resolver = mock(SkuResolver.class);
        when(resolver.resolve(anyString(), any()))
                .thenReturn(new SkuResolver.ResolvedSku(null, null));
        ReturnOrderWorkbookParser parser = new ReturnOrderWorkbookParser(
                new JsonPayloadWriter(new ObjectMapper()),
                resolver
        );

        int sourceRows = 0;
        int importedRows = 0;
        var workbooks = Files.list(ROOT).filter(item -> item.toString().endsWith(".xlsx")).sorted().toList();
        assertThat(workbooks).hasSize(5);
        for (Path path : workbooks) {
            String month = path.getFileName().toString().replaceAll(".*(\\d{4}-\\d{2}).*", "$1");
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
