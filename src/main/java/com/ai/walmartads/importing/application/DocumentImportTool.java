package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Deterministic document parser and persistence tool.
 *
 * <p>Implementations run entirely in local Java code. AI providers are not
 * involved in file discovery, parsing, validation, deduplication, or MySQL
 * persistence.</p>
 */
@FunctionalInterface
public interface DocumentImportTool {

    ImportOutcome importDocument(Path archivePath, ImportFile file) throws IOException;

    record ImportOutcome(int sourceRows, int importedRows) {
    }
}
