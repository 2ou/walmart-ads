package com.ai.walmartads.importing.web;

import com.ai.walmartads.importing.application.FileImportProcessor;
import com.ai.walmartads.importing.application.ImportSource;
import com.ai.walmartads.importing.application.ImportService;
import com.ai.walmartads.importing.application.LocalImportPathService;
import com.ai.walmartads.importing.application.SourceFileCandidate;
import com.ai.walmartads.importing.application.SourceScanner;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.shared.api.GlobalExceptionHandler;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportControllerTest {

    @Test
    void restoresSharedDirectoryScan() throws Exception {
        ImportService importService = mock(ImportService.class);
        LocalImportPathService paths = mock(LocalImportPathService.class);
        Path root = Path.of("D:/PNK/17DATA/source");
        when(paths.currentRoot()).thenReturn(root);
        MockMvc mvc = mvc(importService, paths, null, null);

        mvc.perform(post("/api/imports/scan")
                        .param("source", "DOMESTIC_INVENTORY")
                        .param("source", "RETURN_ORDER")
                        .param("source", "PRODUCT"))
                .andExpect(status().isOk());

        verify(importService).scanAndImport(any(), eq(root));
    }

    @Test
    void restoresSharedDirectoryPreflight() throws Exception {
        ImportService importService = mock(ImportService.class);
        LocalImportPathService paths = mock(LocalImportPathService.class);
        Path root = Path.of("D:/PNK/17DATA/source");
        when(paths.currentRoot()).thenReturn(root);
        when(importService.preflight(any(), eq(root))).thenReturn(List.of());
        MockMvc mvc = mvc(importService, paths, null, null);

        mvc.perform(get("/api/imports/preflight").param("source", "PRODUCTION_ORDER"))
                .andExpect(status().isOk());

        verify(importService).preflight(any(), eq(root));
    }

    @Test
    void acceptsOctetStreamWorkbookUpload() throws Exception {
        ImportService importService = mock(ImportService.class);
        SourceScanner scanner = mock(SourceScanner.class);
        SourceFileCandidate candidate = new SourceFileCandidate(
                Path.of("database-upload/products-upload.xlsx"), Path.of("database-upload/products-upload.xlsx"),
                ImportFileType.PRODUCT, null, null, null);
        when(scanner.uploadedCandidate(eq("products-upload.xlsx"), eq(ImportSource.PRODUCT),
                any(LocalDateTime.class))).thenReturn(candidate);
        when(importService.importUploaded(eq(candidate), eq(ImportSource.PRODUCT), any(byte[].class))).thenReturn(
                new ImportService.ImportRunResult(1L, "SUCCESS", "ok", List.of()));
        MockMvc mvc = mvc(importService, mock(LocalImportPathService.class), scanner, null);
        MockMultipartFile file = new MockMultipartFile(
                "file", "products-upload.xlsx", "application/octet-stream", new byte[]{1, 2, 3});

        WorkspaceContext.set(new WorkspacePrincipal(1L, "PINKSIR", "ACTIVE", "1", "PINKSIR", "PNK", false, 1L));
        try {
            mvc.perform(multipart("/api/imports/upload")
                            .file(file)
                            .param("source", "PRODUCT"))
                    .andExpect(status().isOk());
        } finally {
            WorkspaceContext.clear();
        }
    }

    private static MockMvc mvc(
            ImportService importService,
            LocalImportPathService paths,
            SourceScanner scanner,
            Object ignored
    ) {
        ImportController controller = new ImportController(
                importService,
                mock(FileImportProcessor.class),
                mock(ImportBatchRepository.class),
                mock(ImportFileRepository.class),
                scanner,
                paths
        );
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
