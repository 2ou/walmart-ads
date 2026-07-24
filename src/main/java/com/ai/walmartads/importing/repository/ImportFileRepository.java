package com.ai.walmartads.importing.repository;

import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.AdReportKind;
import com.ai.walmartads.importing.domain.ImportFileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {

    boolean existsBySha256(String sha256);

    List<ImportFile> findByBatchIdOrderById(Long batchId);

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.reportDate = :reportDate
               and file.id <> :keepId
            """)
    int deactivateReportVersion(
            @Param("fileType") ImportFileType fileType,
            @Param("reportDate") LocalDate reportDate,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.reportKind = :reportKind
               and file.reportDate = :reportDate
               and file.id <> :keepId
            """)
    int deactivateAdReportVersion(
            @Param("reportKind") AdReportKind reportKind,
            @Param("reportDate") LocalDate reportDate,
            @Param("keepId") Long keepId
    );

    @Modifying
    @Query("""
            update ImportFile file
               set file.active = false
             where file.fileType = :fileType
               and file.id <> :keepId
            """)
    int deactivateFileTypeVersions(
            @Param("fileType") ImportFileType fileType,
            @Param("keepId") Long keepId
    );
}
