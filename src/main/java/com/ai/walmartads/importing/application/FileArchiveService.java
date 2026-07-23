package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class FileArchiveService {

    private final WalmartAdsProperties properties;

    public FileArchiveService(WalmartAdsProperties properties) {
        this.properties = properties;
    }

    public Path batchDirectory(Long batchId) throws IOException {
        Path directory = properties.archiveRoot().toAbsolutePath().normalize().resolve("batch-" + batchId);
        Files.createDirectories(directory);
        return directory;
    }

    public Path archive(Path batchDirectory, SourceFileCandidate candidate) throws IOException {
        Path target = batchDirectory.resolve(candidate.relativePath()).normalize();
        if (!target.startsWith(batchDirectory)) {
            throw new IllegalArgumentException("归档路径越界：" + candidate.relativePath());
        }
        Files.createDirectories(target.getParent());
        return Files.copy(candidate.path(), target, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
