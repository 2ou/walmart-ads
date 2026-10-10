package com.ai.walmartads.importing.application;

import com.ai.walmartads.config.WalmartAdsProperties;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

@Service
public class LocalImportPathService {

    private final JdbcTemplate jdbc;
    private final WalmartAdsProperties properties;

    public LocalImportPathService(JdbcTemplate jdbc, WalmartAdsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Setting current() {
        long workspaceId = WorkspaceContext.requireId();
        List<String> values = jdbc.queryForList(
                "select local_import_path from wm_ads_workspace where id=?",
                String.class,
                workspaceId
        );
        String configured = values.isEmpty() ? null : values.getFirst();
        Path root = configured == null || configured.isBlank()
                ? defaultSourceRoot()
                : parse(configured);
        return view(root);
    }

    public Setting save(String inputPath) {
        Path root = parse(inputPath);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("本地扫描目录不存在：" + root);
        }
        if (root.getParent() == null) {
            throw new IllegalArgumentException("不能把磁盘根目录作为扫描路径，请选择 source 文件夹");
        }
        int updated = jdbc.update(
                "update wm_ads_workspace set local_import_path=?, updated_at=now(6) where id=?",
                root.toString(),
                WorkspaceContext.requireId()
        );
        if (updated != 1) {
            throw new IllegalArgumentException("当前店铺不存在，无法保存扫描路径");
        }
        return view(root);
    }

    public Path currentRoot() {
        return current().sourceRoot();
    }

    private Path defaultSourceRoot() {
        return properties.dataRoot().toAbsolutePath().normalize().resolve("source");
    }

    private static Path parse(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("请输入本地扫描路径");
        }
        String value = inputPath.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        value = value.replaceFirst("[\\\\/]+\\*$", "");
        if (value.endsWith("*")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.replaceFirst("[\\\\/]+$", "");
        try {
            Path root = Path.of(value);
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("请填写绝对路径，例如 D:\\PNK\\17DATA\\source\\*");
            }
            return root.toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("本地扫描路径格式无效：" + inputPath);
        }
    }

    private static Setting view(Path root) {
        String separator = root.toString().contains("\\") ? "\\" : File.separator;
        return new Setting(root, root + separator + "*", Files.isDirectory(root));
    }

    public record Setting(Path sourceRoot, String displayPath, boolean exists) {
    }
}
