package com.ai.walmartads.workspace;

import com.ai.walmartads.importing.application.ImportSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceOwnershipServiceTest {

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;
    private WorkspaceOwnershipService service;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(new WorkspacePrincipal(1L, "DEMO", "ACTIVE", "1", "Demo shop", "DEMO", false, 1L));
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(1L))).thenReturn("DEMO");
        service = new WorkspaceOwnershipService(jdbc);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void ignoresHiddenTemplateMetadataAndVisibleFieldDescription() throws Exception {
        Path workbookPath = temp.resolve("DEMO0079-upload.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var metadata = workbook.createSheet("Hidden_product_content");
            metadata.createRow(0).createCell(0).setCellValue("SKU");
            metadata.createRow(1).createCell(0).setCellValue("Required");
            workbook.setSheetHidden(0, true);

            var data = workbook.createSheet("Product Content And Site Exp");
            data.createRow(3).createCell(3).setCellValue("SKU");
            data.createRow(4).createCell(3).setCellValue("sku");
            data.createRow(5).createCell(3).setCellValue("Alphanumeric, 50 characters - " + "x".repeat(120));
            data.createRow(6).createCell(3).setCellValue("DEMO0079-C-Army Green-S-WFS");
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        service.verifyUploadedWorkbook(workbookPath, ImportSource.PRODUCT, workbookPath.getFileName().toString());

        verify(jdbc, never()).update(anyString(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void rejectsForeignPrefixFromMemoryAndStoresOnlyDatabaseAudit() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var data = workbook.createSheet("Product Content And Site Exp");
            data.createRow(0).createCell(0).setCellValue("SKU");
            data.createRow(1).createCell(0).setCellValue("ABC001-Blue-M-WFS");
            workbook.write(output);
            content = output.toByteArray();
        }

        assertThrows(IllegalArgumentException.class,
                () -> service.verifyUploadedWorkbook(content, ImportSource.PRODUCT, "foreign.xlsx"));

        verify(jdbc).update(anyString(), eq(1L), eq("PRODUCT"), eq("foreign.xlsx"),
                eq("ABC001-Blue-M-WFS"), org.mockito.ArgumentMatchers.contains("允许前缀"),
                isNull(), isNull());
    }

    @Test
    void findsCampaignStylesUsingOnlyPrefixesStoredForTheCurrentWorkspace() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(1L))).thenReturn("DEMO, ALT-");
        var findStyle = service.currentParentSkuFinder();
        assertEquals("DEMO0079", findStyle.apply("OTHER001 - demo0079 exact"));
        assertEquals("ALT-302", findStyle.apply("alt-302 auto"));
        assertNull(findStyle.apply("OTHER001 auto"));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(1L))).thenReturn(null);
        assertNull(service.currentParentSkuFinder().apply("DEMO0079"));
    }
}
