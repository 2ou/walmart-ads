package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.application.ImportSource;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportControllerTest {

    @Test
    void passesRepeatedBusinessSourceParametersToImportService() throws Exception {
        ImportService importService = mock(ImportService.class);
        when(importService.scanAndImport(Set.of(
                ImportSource.ORDER,
                ImportSource.ITEM_KEYWORD_PERFORMANCE,
                ImportSource.PRODUCT
        ))).thenReturn(new ImportService.ImportRunResult(
                19L,
                "SUCCESS",
                "发现 3 个文件，导入 3，跳过 0，失败 0",
                List.of()
        ));
        ImportController controller = new ImportController(
                importService,
                mock(FileImportProcessor.class),
                mock(ImportBatchRepository.class),
                mock(ImportFileRepository.class)
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/imports/scan")
                        .param("source", "ORDER")
                        .param("source", "ITEM_KEYWORD_PERFORMANCE")
                        .param("source", "PRODUCT"))
                .andExpect(status().isOk());

        verify(importService).scanAndImport(Set.of(
                ImportSource.ORDER,
                ImportSource.ITEM_KEYWORD_PERFORMANCE,
                ImportSource.PRODUCT
        ));
    }
}
