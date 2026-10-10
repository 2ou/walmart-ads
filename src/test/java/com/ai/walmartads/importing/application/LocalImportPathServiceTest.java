package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalImportPathServiceTest {

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;
    private LocalImportPathService service;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(new WorkspacePrincipal(7L, "SHOP", "ACTIVE", "7", "Shop", "SHP", false, 1L));
        jdbc = mock(JdbcTemplate.class);
        WalmartAdsProperties properties = new WalmartAdsProperties(temp, temp.resolve("archive"), null, null, null);
        service = new LocalImportPathService(jdbc, properties);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void acceptsAndStripsTrailingWildcard() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source"));
        when(jdbc.update(any(String.class), eq(source.toString()), eq(7L))).thenReturn(1);

        LocalImportPathService.Setting saved = service.save(source + "\\*");

        assertThat(saved.sourceRoot()).isEqualTo(source.toAbsolutePath().normalize());
        assertThat(saved.displayPath()).endsWith("source\\*");
        assertThat(saved.exists()).isTrue();
    }

    @Test
    void refusesMissingDirectory() {
        assertThatThrownBy(() -> service.save(temp.resolve("missing") + "\\*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目录不存在");
    }
}
