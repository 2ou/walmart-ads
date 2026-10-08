package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.shared.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportControllerTest {

    @Test
    void rejectsLegacySharedDirectoryScan() throws Exception {
        ImportService importService = mock(ImportService.class);
        MockMvc mvc = mvc(importService);

        mvc.perform(post("/api/imports/scan")
                        .param("source", "DOMESTIC_INVENTORY")
                        .param("source", "RETURN_ORDER")
                        .param("source", "PRODUCT"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(importService);
    }

    @Test
    void rejectsLegacySharedDirectoryPreflight() throws Exception {
        ImportService importService = mock(ImportService.class);
        MockMvc mvc = mvc(importService);

        mvc.perform(get("/api/imports/preflight").param("source", "PRODUCTION_ORDER"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(importService);
    }

    private static MockMvc mvc(ImportService importService) {
        ImportController controller = new ImportController(
                importService,
                mock(FileImportProcessor.class),
                mock(ImportBatchRepository.class),
                mock(ImportFileRepository.class)
        );
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
