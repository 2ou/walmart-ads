package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportFileType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadCandidateFactoryTest {

    private final UploadCandidateFactory factory = new UploadCandidateFactory();

    @Test
    void classifiesMonthlyReturnUploadWithoutCreatingALocalFile() {
        SourceFileCandidate candidate = factory.create(
                "Customer returns-202610.xlsx",
                ImportSource.RETURN_ORDER,
                LocalDateTime.of(2026, 10, 27, 8, 0)
        );

        assertThat(candidate.fileType()).isEqualTo(ImportFileType.RETURN_ORDER);
        assertThat(candidate.reportDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(candidate.path().toString()).startsWith("database-upload");
        assertThat(candidate.snapshotAt()).isNull();
    }

    @Test
    void rejectsReturnUploadWithoutCanonicalMonthInFileName() {
        assertThatThrownBy(() -> factory.create(
                "returns.xlsx",
                ImportSource.RETURN_ORDER,
                LocalDateTime.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer returns-YYYYMM.xlsx");
    }
}
