package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportServiceTest {

    private FileFingerprint fingerprint;
    private FileImportProcessor processor;
    private ImportFileRepository fileRepository;
    private ImportService service;

    @BeforeEach
    void setUp() {
        fingerprint = mock(FileFingerprint.class);
        processor = mock(FileImportProcessor.class);
        fileRepository = mock(ImportFileRepository.class);
        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        when(batchRepository.save(any(ImportBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ImportService(fingerprint, processor, batchRepository, fileRepository);
    }

    @Test
    void importsBrowserUploadFromMemoryWithoutReadingALocalPath() throws Exception {
        byte[] content = {1, 2, 3, 4};
        SourceFileCandidate candidate = new SourceFileCandidate(
                Path.of("database-upload/products-upload.xlsx"),
                Path.of("database-upload/products-upload.xlsx"),
                ImportFileType.PRODUCT,
                null,
                null,
                null
        );
        when(fingerprint.sha256(content)).thenReturn("content-sha");
        when(processor.importFingerprint(ImportFileType.PRODUCT, "content-sha")).thenReturn("sha-product");
        when(fileRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(processor.processUploaded(any(), any(byte[].class), anyString()))
                .thenReturn(new DocumentImportTool.ImportOutcome(2, 2));

        ImportService.ImportRunResult result = service.importUploaded(candidate, ImportSource.PRODUCT, content);

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.status()).isEqualTo("IMPORTED");
            assertThat(file.importedRows()).isEqualTo(2);
            assertThat(file.path()).startsWith("database-upload");
        });
        verify(processor).processUploaded(any(), any(byte[].class),
                org.mockito.ArgumentMatchers.startsWith("database://workspace-upload/"));
        verify(fingerprint, never()).sha256(any(Path.class));
    }
}
