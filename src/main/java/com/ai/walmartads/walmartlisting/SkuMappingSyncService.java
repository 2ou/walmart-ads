package com.ai.walmartads.walmartlisting;

import com.ai.walmartads.importing.domain.ImportBatch;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.SkuMapping;
import com.ai.walmartads.importing.parser.ImportValues;
import com.ai.walmartads.importing.parser.JsonPayloadWriter;
import com.ai.walmartads.importing.repository.ImportBatchRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.OrderRecordRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SKU / Item / UPC 映射（{@code wm_ads_sku_mapping}）的**唯一写入方** —— 数据源由本地
 * WFS 上传模板（{@code SkuMappingWorkbookParser}）换成领星 MCP 在线商品
 * （{@code platform_listing_walmart_list}，2026-09-28 换源）。
 *
 * <h2>为什么换源</h2>
 * <ul>
 *   <li>领星在线商品已覆盖全部在售 + 历史下架款（实测 1,020 条 &gt; 模板的 951 条），
 *       历史评论 / 退货的 Item ID 归款率更高；</li>
 *   <li>该接口本来就是每小时同步（{@link WalmartListingSyncService}），顺带写入零额外 API 调用；</li>
 *   <li>模板行里的 {@code local_name} 即领星自维护的款号，比按连字符截取 SKU 前缀更权威。</li>
 * </ul>
 *
 * <h2>写入语义（整窗替换 + 单行复用）</h2>
 * <p>与文件导入时代的差异有两点：
 * <ol>
 *   <li><b>物理清空</b>再整窗写入（{@link SkuMappingRepository#deleteAllRows()}），而非只翻
 *       {@code current_version} 标记 —— 因为下面第 2 点让「按旧版本 import_file 清理」永远命中不到；</li>
 *   <li>复用同一条 {@code wm_ads_import_file} 行（{@code import_file_id} 是 NOT NULL 外键），
 *       避免按小时新增 file / batch 记录。</li>
 * </ol>
 *
 * <h2>幂等</h2>
 * <p>以「规范化后数据集的 SHA-256」为指纹：与当前行一致则整段跳过写库（仅补跑订单 UPC 回填），
 * 因此每小时一次的调度在无数据变化时几乎无写放大。
 */
@Service
public class SkuMappingSyncService {

    private static final Logger log = LoggerFactory.getLogger(SkuMappingSyncService.class);

    /** 复用行的固定源标识，同时用作 import_batch.source_root。 */
    static final String SOURCE_PATH = "lingxing://platform_listing_walmart_list";

    private static final String EMPTY_SHA = "0".repeat(64);

    private final ImportFileRepository fileRepository;
    private final ImportBatchRepository batchRepository;
    private final SkuMappingRepository mappingRepository;
    private final OrderRecordRepository orderRepository;
    private final JsonPayloadWriter json;

    public SkuMappingSyncService(
            ImportFileRepository fileRepository,
            ImportBatchRepository batchRepository,
            SkuMappingRepository mappingRepository,
            OrderRecordRepository orderRepository,
            JsonPayloadWriter json
    ) {
        this.fileRepository = fileRepository;
        this.batchRepository = batchRepository;
        this.mappingRepository = mappingRepository;
        this.orderRepository = orderRepository;
        this.json = json;
    }

    /**
     * 用一次在线商品拉取结果整窗重写映射表。
     *
     * <p>独立事务（{@code REQUIRES_NEW}）：写映射失败不得回滚同批的在线商品/库存快照写入。
     *
     * @param listings 领星 {@code platform_listing_walmart_list} 全量结果
     * @return 本次写入统计
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RewriteSummary rewrite(List<WalmartListingDto> listings) {
        List<MappingRow> rows = normalize(listings);
        if (rows.isEmpty()) {
            log.warn("领星在线商品未产出任何可用映射行（源 {} 条），保留现有映射表不动", listings == null ? 0 : listings.size());
            return new RewriteSummary(0, 0, true);
        }
        String sha = sha256(rows);
        Optional<ImportFile> existing = fileRepository
                .findFirstByFileTypeAndActiveTrueOrderByIdDesc(ImportFileType.SKU_MAPPING);

        if (existing.isPresent() && sha.equals(existing.get().getSha256())) {
            // 数据集无变化：跳过清空/重写，但仍回填一次订单 UPC（每天都有新订单进来）。
            orderRepository.backfillUpcFromCurrentMappings();
            log.debug("SKU 映射无变化，跳过重写（{} 行）", rows.size());
            return new RewriteSummary(rows.size(), 0, true);
        }

        ImportFile file = existing.orElseGet(() -> createSourceFile(sha));
        LocalDateTime now = LocalDateTime.now();

        mappingRepository.deleteAllRows();
        List<SkuMapping> entities = new ArrayList<>(rows.size());
        for (MappingRow row : rows) {
            entities.add(new SkuMapping(
                    file,
                    now,
                    row.sku(),
                    row.parentSku(),
                    row.itemId(),
                    row.gtin(),
                    row.upc(),
                    row.itemName(),
                    row.raw()
            ));
        }
        mappingRepository.saveAllAndFlush(entities);
        orderRepository.backfillUpcFromCurrentMappings();

        file.refreshFromSync(SOURCE_PATH, now, sha, rows.size(), entities.size());
        fileRepository.save(file);
        log.info("SKU 映射已按领星在线商品整窗重写：rows={}, fileId={}", entities.size(), file.getId());
        return new RewriteSummary(rows.size(), entities.size(), false);
    }

    /**
     * 规范化：过滤掉没有 SKU / Item ID 的行；款号取领星 {@code local_name}，缺失才回落 SKU 前缀截取。
     *
     * <p>{@code gtin} / {@code upc} 在表上是 NOT NULL，源侧缺失时写空串（不因缺少条码丢弃整条映射 ——
     * 映射的主要用途是 Item ID → 款号）。
     */
    private List<MappingRow> normalize(List<WalmartListingDto> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }
        Map<String, MappingRow> bySku = new LinkedHashMap<>();
        for (WalmartListingDto dto : listings) {
            String sku = trimToNull(dto.sku);
            String itemId = trimToNull(dto.itemId);
            if (sku == null || itemId == null) {
                continue;
            }
            String parentSku = trimToNull(dto.parentSku);
            if (parentSku == null) {
                parentSku = trimToNull(ImportValues.parentSku(sku));
            }
            if (parentSku == null) {
                continue;
            }
            String gtin = trimToNull(dto.gtin);
            String upc = normalizedUpc(trimToNull(dto.upc), gtin);
            bySku.putIfAbsent(sku, new MappingRow(
                    sku,
                    parentSku,
                    itemId,
                    gtin == null ? "" : gtin,
                    upc == null ? "" : upc,
                    trimToNull(dto.title),
                    json.write(dto)
            ));
        }
        return new ArrayList<>(bySku.values());
    }

    private ImportFile createSourceFile(String sha) {
        ImportBatch batch = batchRepository.save(new ImportBatch(SOURCE_PATH));
        ImportFile file = new ImportFile(
                batch,
                ImportFileType.SKU_MAPPING,
                null,
                "platform_listing_walmart_list",
                SOURCE_PATH,
                sha == null ? EMPTY_SHA : sha,
                0L,
                LocalDateTime.now(),
                null,
                null
        );
        return fileRepository.save(file);
    }

    /** 与 {@code SkuMappingWorkbookParser#normalizedUpc} 保持同一口径。 */
    private static String normalizedUpc(String upc, String gtin) {
        if (upc != null && upc.matches("\\d{12,14}")) {
            return upc;
        }
        if (gtin != null && gtin.matches("\\d{14}") && gtin.startsWith("00")) {
            return gtin.substring(2);
        }
        return upc;
    }

    /** 内容指纹：对规范化后的关键列做稳定拼接再取 SHA-256。 */
    private static String sha256(List<MappingRow> rows) {
        StringBuilder payload = new StringBuilder(rows.size() * 120);
        for (MappingRow row : rows) {
            payload.append(row.sku()).append('|')
                    .append(row.parentSku()).append('|')
                    .append(row.itemId()).append('|')
                    .append(row.gtin()).append('|')
                    .append(row.upc()).append('|')
                    .append(row.itemName() == null ? "" : row.itemName())
                    .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 规范化后的映射行（写库前的中间形态）。 */
    private record MappingRow(
            String sku,
            String parentSku,
            String itemId,
            String gtin,
            String upc,
            String itemName,
            String raw
    ) {
    }

    /** 写入统计。{@code skipped=true} 表示本次未写库（指纹一致，或源侧未产出任何可用行）。 */
    public record RewriteSummary(int normalizedRows, int writtenRows, boolean skipped) {
    }
}
