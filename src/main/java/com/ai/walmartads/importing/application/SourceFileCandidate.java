package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportFileType;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SourceFileCandidate(
        Path path,
        Path relativePath,
        ImportFileType fileType,
        LocalDate reportDate,
        LocalDateTime snapshotAt
) {
}
