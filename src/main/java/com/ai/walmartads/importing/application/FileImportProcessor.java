package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.AdCampaignItemSnapshot;
import com.ai.walmartads.importing.domain.AdCampaignSnapshot;
import com.ai.walmartads.importing.domain.DemandForecastRecord;
import com.ai.walmartads.importing.domain.DomesticInventorySnapshot;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.ai.walmartads.importing.domain.OrderRecord;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import com.ai.walmartads.importing.domain.SkuMapping;
import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.parser.AdCsvParser;
import com.ai.walmartads.importing.parser.CampaignSnapshotJsonParser;
import com.ai.walmartads.importing.parser.CampaignSnapshotRows;
import com.ai.walmartads.importing.parser.DemandForecastParser;
import com.ai.walmartads.importing.parser.DomesticInventoryWorkbookParser;
import com.ai.walmartads.importing.parser.HistoricalWorkbookParser;
import com.ai.walmartads.importing.parser.HistoricalWorkbookRows;
import com.ai.walmartads.importing.parser.InventoryWorkbookParser;
import com.ai.walmartads.importing.parser.OrderWorkbookParser;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.parser.ProductWorkbookParser;
import com.ai.walmartads.importing.parser.ProductionOrderParser;
import com.ai.walmartads.importing.parser.ReturnOrderWorkbookParser;
import com.ai.walmartads.importing.parser.SkuMappingWorkbookParser;
import com.ai.walmartads.importing.parser.ShipmentLogisticsWorkbookParser;
import com.ai.walmartads.importing.parser.ShipmentWorkbookParser;
import com.ai.walmartads.importing.parser.WalmartListingParser;
import com.ai.walmartads.importing.parser.WalmartListingRow;
import com.ai.walmartads.importing.parser.WeeklyInventoryWorkbookParser;
import com.ai.walmartads.importing.repository.AdPerformanceRepository;
import com.ai.walmartads.importing.repository.AdCampaignItemSnapshotRepository;
import com.ai.walmartads.importing.repository.AdCampaignSnapshotRepository;
import com.ai.walmartads.importing.repository.DemandForecastRepository;
import com.ai.walmartads.importing.repository.DomesticInventorySnapshotRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.OrderRecordRepository;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.importing.repository.ProductionOrderRepository;
import com.ai.walmartads.importing.repository.ReturnOrderRecordRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import com.ai.walmartads.importing.repository.ShipmentLogisticsRepository;
import com.ai.walmartads.importing.repository.ShipmentRecordRepository;
import com.ai.walmartads.inventory.application.TransitInventoryService;
import com.ai.walmartads.inventory.application.ReplenishmentPlanCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FileImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(FileImportProcessor.class);

    private final ImportFileRepository fileRepository;
    private final OrderRecordRepository orderRepository;
    private final AdPerformanceRepository adRepository;
    private final AdCampaignSnapshotRepository campaignSnapshotRepository;
    private final AdCampaignItemSnapshotRepository campaignItemSnapshotRepository;
    private final InventorySnapshotRepository inventoryRepository;
    private final ProductRecordRepository productRepository;
    private final SkuMappingRepository mappingRepository;
    private final ReturnOrderRecordRepository returnOrderRepository;
    private final OrderWorkbookParser orderParser;
    private final AdCsvParser adParser;
    private final CampaignSnapshotJsonParser campaignSnapshotParser;
    private final InventoryWorkbookParser inventoryParser;
    private final WeeklyInventoryWorkbookParser weeklyInventoryParser;
    private final ProductWorkbookParser productParser;
    private final HistoricalWorkbookParser historicalParser;
    private final SkuMappingWorkbookParser mappingParser;
    private final ReturnOrderWorkbookParser returnOrderParser;
    private final WalmartListingParser walmartListingParser;
    private final DemandForecastParser demandForecastParser;
    private final DemandForecastRepository demandForecastRepository;
    private final DomesticInventoryWorkbookParser domesticInventoryParser;
    private final DomesticInventorySnapshotRepository domesticInventoryRepository;
    private final ProductionOrderParser productionOrderParser;
    private final ProductionOrderRepository productionOrderRepository;
    private final ShipmentWorkbookParser shipmentParser;
    private final ShipmentLogisticsWorkbookParser shipmentLogisticsParser;
    private final ShipmentRecordRepository shipmentRepository;
    private final ShipmentLogisticsRepository shipmentLogisticsRepository;
    private final TransitInventoryService transitInventoryService;
    private final ReplenishmentPlanCache replenishmentPlanCache;
    private final Map<ImportFileType, DocumentImportTool> tools;
    private final Map<ImportFileType, String> toolVersions;

    public FileImportProcessor(
            ImportFileRepository fileRepository,
            OrderRecordRepository orderRepository,
            AdPerformanceRepository adRepository,
            AdCampaignSnapshotRepository campaignSnapshotRepository,
            AdCampaignItemSnapshotRepository campaignItemSnapshotRepository,
            InventorySnapshotRepository inventoryRepository,
            ProductRecordRepository productRepository,
            SkuMappingRepository mappingRepository,
            ReturnOrderRecordRepository returnOrderRepository,
            OrderWorkbookParser orderParser,
            AdCsvParser adParser,
            CampaignSnapshotJsonParser campaignSnapshotParser,
            InventoryWorkbookParser inventoryParser,
            WeeklyInventoryWorkbookParser weeklyInventoryParser,
            ProductWorkbookParser productParser,
            HistoricalWorkbookParser historicalParser,
            SkuMappingWorkbookParser mappingParser,
            ReturnOrderWorkbookParser returnOrderParser,
            WalmartListingParser walmartListingParser,
            DemandForecastParser demandForecastParser,
            DemandForecastRepository demandForecastRepository,
            DomesticInventoryWorkbookParser domesticInventoryParser,
            DomesticInventorySnapshotRepository domesticInventoryRepository,
            ProductionOrderParser productionOrderParser,
            ProductionOrderRepository productionOrderRepository,
            ShipmentWorkbookParser shipmentParser,
            ShipmentLogisticsWorkbookParser shipmentLogisticsParser,
            ShipmentRecordRepository shipmentRepository,
            ShipmentLogisticsRepository shipmentLogisticsRepository,
            TransitInventoryService transitInventoryService,
            ReplenishmentPlanCache replenishmentPlanCache
    ) {
        this.fileRepository = fileRepository;
        this.orderRepository = orderRepository;
        this.adRepository = adRepository;
        this.campaignSnapshotRepository = campaignSnapshotRepository;
        this.campaignItemSnapshotRepository = campaignItemSnapshotRepository;
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.mappingRepository = mappingRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.orderParser = orderParser;
        this.adParser = adParser;
        this.campaignSnapshotParser = campaignSnapshotParser;
        this.inventoryParser = inventoryParser;
        this.weeklyInventoryParser = weeklyInventoryParser;
        this.productParser = productParser;
        this.historicalParser = historicalParser;
        this.mappingParser = mappingParser;
        this.returnOrderParser = returnOrderParser;
        this.walmartListingParser = walmartListingParser;
        this.demandForecastParser = demandForecastParser;
        this.demandForecastRepository = demandForecastRepository;
        this.domesticInventoryParser = domesticInventoryParser;
        this.domesticInventoryRepository = domesticInventoryRepository;
        this.productionOrderParser = productionOrderParser;
        this.productionOrderRepository = productionOrderRepository;
        this.shipmentParser = shipmentParser;
        this.shipmentLogisticsParser = shipmentLogisticsParser;
        this.shipmentRepository = shipmentRepository;
        this.shipmentLogisticsRepository = shipmentLogisticsRepository;
        this.transitInventoryService = transitInventoryService;
        this.replenishmentPlanCache = replenishmentPlanCache;
        this.tools = new EnumMap<>(ImportFileType.class);
        this.toolVersions = new EnumMap<>(ImportFileType.class);
        register("legacy", (path, file) -> saveOrders(orderParser.parse(path, file)), ImportFileType.ORDER);
        register(
                "legacy",
                (path, file) -> saveAds(adParser.parse(path, file)),
                ImportFileType.AD_REPORT,
                ImportFileType.AD_AUTO,
                ImportFileType.AD_MANUAL
        );
        register(
                "legacy",
                (path, file) -> saveInventory(inventoryParser.parse(path, file)),
                ImportFileType.INVENTORY
        );
        register(
                "legacy",
                (path, file) -> saveInventory(weeklyInventoryParser.parse(path, file)),
                ImportFileType.WEEKLY_INVENTORY
        );
        register(
                "legacy",
                (path, file) -> saveProducts(productParser.parse(path, file)),
                ImportFileType.PRODUCT
        );
        register(
                "legacy",
                (path, file) -> saveHistory(historicalParser.parse(path, file), file),
                ImportFileType.HISTORY
        );
        register(
                "legacy",
                (path, file) -> saveSkuMappings(mappingParser.parse(path, file), file),
                ImportFileType.SKU_MAPPING
        );
        register(
                "2",
                (path, file) -> saveReturnOrders(returnOrderParser.parse(path, file), file),
                ImportFileType.RETURN_ORDER
        );
        register(
                "1",
                (path, file) -> saveDemandForecasts(demandForecastParser.parse(path, file)),
                ImportFileType.DEMAND_FORECAST
        );
        register(
                "1",
                (path, file) -> saveWalmartListings(walmartListingParser.parse(path, file)),
                ImportFileType.WALMART_LISTING
        );
        register(
                "1",
                (path, file) -> saveDomesticInventory(domesticInventoryParser.parse(path, file)),
                ImportFileType.DOMESTIC_INVENTORY
        );
        register(
                "3",
                (path, file) -> saveProductionOrders(productionOrderParser.parse(path, file), file),
                ImportFileType.PRODUCTION_ORDER
        );
        register(
                "1",
                (path, file) -> saveShipments(shipmentParser.parse(path, file), file),
                ImportFileType.SHIPMENT
        );
        register(
                "1",
                (path, file) -> saveShipmentLogistics(shipmentLogisticsParser.parse(path, file), file),
                ImportFileType.SHIPMENT_LOGISTICS
        );
        register(
                "1",
                (path, file) -> saveCampaignSnapshot(campaignSnapshotParser.parse(path, file)),
                ImportFileType.CAMPAIGN_SNAPSHOT
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentImportTool.ImportOutcome process(Long fileId, Path archivePath) throws IOException {
        ImportFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("导入文件记录不存在：" + fileId));
        DocumentImportTool tool = tools.get(file.getFileType());
        if (tool == null) {
            throw new IllegalArgumentException("No code import tool registered for " + file.getFileType());
        }
        DocumentImportTool.ImportOutcome result = tool.importDocument(archivePath, file);
        file.imported(archivePath.toString(), result.sourceRows(), result.importedRows());
        if (file.getReportDate() != null && file.getReportKind() != null) {
            fileRepository.deactivateAdReportVersion(file.getReportKind(), file.getReportDate(), file.getId());
        } else if (file.getFileType() == ImportFileType.INVENTORY && file.getSnapshotAt() != null) {
            fileRepository.deactivateInventorySnapshotVersion(
                    ImportFileType.INVENTORY,
                    file.getSnapshotAt(),
                    file.getId()
            );
        } else if (file.getReportDate() != null && file.getFileType() != ImportFileType.PRODUCT) {
            fileRepository.deactivateReportVersion(file.getFileType(), file.getReportDate(), file.getId());
        } else if (file.getFileType() == ImportFileType.HISTORY
                || file.getFileType() == ImportFileType.SKU_MAPPING
                || file.getFileType() == ImportFileType.PRODUCTION_ORDER
                || file.getFileType() == ImportFileType.SHIPMENT
                || file.getFileType() == ImportFileType.SHIPMENT_LOGISTICS
                || file.getFileType() == ImportFileType.CAMPAIGN_SNAPSHOT) {
            fileRepository.deactivateFileTypeVersions(file.getFileType(), file.getId());
        }
        try {
            replenishmentPlanCache.refreshAfterSuccessfulImport(file.getFileType());
        } catch (RuntimeException exception) {
            log.warn("Replenishment forecast refresh failed after importing {}: {}", file.getFileType(), exception.getMessage());
        }
        return result;
    }

    public Set<ImportFileType> supportedTypes() {
        return Set.copyOf(tools.keySet());
    }

    public String toolVersion(ImportFileType type) {
        return toolVersions.getOrDefault(type, "unregistered");
    }

    public String importFingerprint(ImportFileType type, String contentSha256) {
        String version = toolVersion(type);
        if ("legacy".equals(version)) {
            return contentSha256;
        }
        String source = contentSha256 + "|" + type.name() + "|" + version;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void register(String version, DocumentImportTool tool, ImportFileType... types) {
        for (ImportFileType type : types) {
            tools.put(type, tool);
            toolVersions.put(type, version);
        }
    }

    private DocumentImportTool.ImportOutcome saveOrders(ParsedRows<OrderRecord> parsed) {
        orderRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveAds(ParsedRows<AdPerformance> parsed) {
        adRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveInventory(ParsedRows<InventorySnapshot> parsed) {
        inventoryRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveProducts(ParsedRows<ProductRecord> parsed) {
        List<String> skus = parsed.rows().stream()
                .map(ProductRecord::getSku)
                .distinct()
                .toList();
        Map<String, ProductRecord> previous = Map.of();
        if (!skus.isEmpty()) {
            // 重新导入产品库时，保留此前由「沃尔玛商品」扫描回写的列，避免被上传文件里的空值覆盖。
            previous = productRepository.findAllBySkuInAndCurrentVersionTrue(skus).stream()
                    .collect(Collectors.toMap(ProductRecord::getSku, record -> record, (first, second) -> first));
            productRepository.deactivateCurrentVersions(skus);
        }
        for (ProductRecord record : parsed.rows()) {
            ProductRecord old = previous.get(record.getSku());
            if (old != null) {
                if (record.getPrice() == null) record.setPrice(old.getPrice());
                if (record.getUpc() == null) record.setUpc(old.getUpc());
                if (record.getGtin() == null) record.setGtin(old.getGtin());
                if (record.getListingStatus() == null) record.setListingStatus(old.getListingStatus());
                if (record.getStore() == null) record.setStore(old.getStore());
                if (record.getProductId() == null) record.setProductId(old.getProductId());
            }
        }
        productRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveHistory(HistoricalWorkbookRows parsed, ImportFile file) {
        deleteSupersededHistoryRows(file);
        orderRepository.saveAll(parsed.orders());
        adRepository.saveAll(parsed.ads());
        return new DocumentImportTool.ImportOutcome(
                parsed.sourceRowCount(),
                parsed.orders().size() + parsed.ads().size()
        );
    }

    /**
     * HISTORY 报表「整类型快照替换」：写入当前版本前，先物理删除该类型所有历史版本（含已停用版本
     * 遗留的死行）关联的广告与订单明细，使历史区间数据恒等于本次文件内容的精确快照。
     *
     * <p>HISTORY 一份文件覆盖整个历史区间、{@code reportDate} 为 NULL，故按类型整体定位旧版本，
     * 不能用退货那种按报表月的 {@code findOtherVersionIds}。
     *
     * <p>此前 process() 只对 HISTORY 调用 {@code deactivateFileTypeVersions} 把旧 import_file 置
     * active=0，从不删明细，于是每重导一次就多留一份完整副本 —— 2026-09 排查时发现库内滞留 5 份
     * 逐字段完全相同的副本（广告 206,155 行 / 订单 71,315 行，均为生效版本的 5 整倍）。
     *
     * <p>与 {@code process()} 里的 {@code deactivateFileTypeVersions} 配合：本方法与其处于同一个
     * {@code REQUIRES_NEW} 事务内，中途失败会整体回滚，不会出现「旧行已删、新行未进」的空窗。
     *
     * <p>只按「该类型此前贡献过的行」删除（以 import_file 定位），不按行内业务日期删除 ——
     * 后者会误删仍生效的版本或其他类型报表的行。
     */
    private void deleteSupersededHistoryRows(ImportFile file) {
        List<Long> supersededIds = fileRepository.findOtherVersionIdsByType(
                file.getFileType(),
                file.getId()
        );
        if (supersededIds.isEmpty()) {
            return;
        }
        int ads = adRepository.deleteByImportFileIdIn(supersededIds);
        int orders = orderRepository.deleteByImportFileIdIn(supersededIds);
        log.info(
                "HISTORY {} 整类型快照替换：清理历史版本 id={} 的 {} 条广告明细 + {} 条订单明细",
                file.getFileName(),
                supersededIds,
                ads,
                orders
        );
    }

    /**
     * 单表明细的「整类型快照替换」通用清理：定位该类型所有历史版本（含已停用版本遗留的死行），
     * 物理删除其关联明细，使该类型数据恒等于本次文件内容的精确快照。
     *
     * <p>适用对象：SKuMapping / ProductionOrder / Shipment / ShipmentLogistics 这类「整类型作废」
     * 报表 —— 它们的 {@code deactivateAllCurrentVersions()} 只把旧行标记为 currentVersion=false，
     * 从不删行，于是每重导一次就多留一份完整副本（实测四类合计滞留约 9,500 行 stale）。
     *
     * <p>与 HISTORY 走同一事务边界（{@code process()} 的 {@code REQUIRES_NEW}），中途失败整体回滚，
     * 不会出现「旧行已删、新行未进」的空窗。
     *
     * @param label  日志用的人类可读类型名
     * @param deleter 该类型的明细删除方法（repository 方法引用）
     */
    private void deleteSupersededRowsByType(
            ImportFile file,
            String label,
            Function<Collection<Long>, Integer> deleter
    ) {
        List<Long> supersededIds = fileRepository.findOtherVersionIdsByType(
                file.getFileType(),
                file.getId()
        );
        if (supersededIds.isEmpty()) {
            return;
        }
        int deleted = deleter.apply(supersededIds);
        log.info(
                "{} {} 整类型快照替换：清理历史版本 id={} 的 {} 条明细",
                label,
                file.getFileName(),
                supersededIds,
                deleted
        );
    }

    private DocumentImportTool.ImportOutcome saveSkuMappings(ParsedRows<SkuMapping> parsed, ImportFile file) {
        deleteSupersededRowsByType(file, "SKU映射", mappingRepository::deleteByImportFileIdIn);
        mappingRepository.deactivateCurrentVersions();
        mappingRepository.saveAllAndFlush(parsed.rows());
        adRepository.backfillSkuFromCurrentMappings();
        orderRepository.backfillUpcFromCurrentMappings();
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveReturnOrders(
            ParsedRows<ReturnOrderRecord> parsed,
            ImportFile file
    ) {
        deleteSupersededReturnMonthRows(file);
        returnOrderRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    /**
     * 退货报表「整月快照替换」：写入当前版本前，先删除该报表月所有历史版本（含已停用版本遗留的死行）
     * 关联的明细，使该月数据恒等于文件内容的精确快照。
     *
     * <p>与 {@code process()} 里的 {@code deactivateReportVersion} 配合：本方法与其处于同一个
     * {@code REQUIRES_NEW} 事务内，中途失败会整体回滚，不会出现「旧行已删、新行未进」的空窗。
     *
     * <p>只按「该文件此前贡献过的行」删除（以 import_file 定位），不按行内业务日期删除 ——
     * 后者会误删其它月份报表里同月发起的退货（7 月报表常含 6 月发起的退货）。
     */
    private void deleteSupersededReturnMonthRows(ImportFile file) {
        if (file.getReportDate() == null) {
            return;
        }
        List<Long> supersededIds = fileRepository.findOtherVersionIds(
                file.getFileType(),
                file.getReportDate(),
                file.getId()
        );
        if (supersededIds.isEmpty()) {
            return;
        }
        int deleted = returnOrderRepository.deleteByImportFileIdIn(supersededIds);
        log.info(
                "退货报表 {} 整月快照替换：清理历史版本 id={} 的 {} 条明细",
                file.getFileName(),
                supersededIds,
                deleted
        );
    }

    private DocumentImportTool.ImportOutcome saveWalmartListings(ParsedRows<WalmartListingRow> parsed) {
        List<String> skus = parsed.rows().stream()
                .map(WalmartListingRow::sku)
                .distinct()
                .toList();
        if (skus.isEmpty()) {
            return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), 0);
        }
        List<ProductRecord> products = productRepository.findAllBySkuInAndCurrentVersionTrue(skus);
        Map<String, ProductRecord> bySku = products.stream()
                .collect(Collectors.toMap(ProductRecord::getSku, record -> record, (first, second) -> first));
        int matched = 0;
        for (WalmartListingRow row : parsed.rows()) {
            ProductRecord product = bySku.get(row.sku());
            if (product == null) {
                // 不在产品库的 SKU 不参与分析，按现有口径跳过回写。
                continue;
            }
            product.setPrice(row.price());
            product.setUpc(row.upc());
            product.setGtin(row.gtin());
            product.setListingStatus(row.listingStatus());
            product.setStore(row.store());
            if (row.productId() != null) {
                product.setProductId(row.productId());
            }
            matched++;
        }
        productRepository.saveAll(products);
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), matched);
    }

    private DocumentImportTool.ImportOutcome saveDemandForecasts(ParsedRows<DemandForecastRecord> parsed) {
        List<String> skus = parsed.rows().stream()
                .map(DemandForecastRecord::getSku)
                .distinct()
                .toList();
        if (!skus.isEmpty()) {
            demandForecastRepository.deactivateCurrentVersions(skus);
        }
        demandForecastRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveDomesticInventory(ParsedRows<DomesticInventorySnapshot> parsed) {
        domesticInventoryRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveProductionOrders(ParsedRows<ProductionOrderRecord> parsed, ImportFile file) {
        // 整类型作废：重新导入时先清掉历史版本的明细行，再作废全部旧标记，仅保留本次导入
        // （对应「只放一个文件、以最新扫描为准」；只标记不删行会让每重导一次就多留一份完整副本）
        deleteSupersededRowsByType(file, "生产订单", productionOrderRepository::deleteByImportFileIdIn);
        productionOrderRepository.deactivateAllCurrentVersions();
        productionOrderRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveShipments(ParsedRows<ShipmentRecord> parsed, ImportFile file) {
        deleteSupersededRowsByType(file, "发货记录", shipmentRepository::deleteByImportFileIdIn);
        shipmentRepository.deactivateAllCurrentVersions();
        shipmentRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveShipmentLogistics(ParsedRows<ShipmentLogisticsRecord> parsed, ImportFile file) {
        deleteSupersededRowsByType(file, "物流跟踪", shipmentLogisticsRepository::deleteByImportFileIdIn);
        shipmentLogisticsRepository.deactivateAllCurrentVersions();
        shipmentLogisticsRepository.saveAll(parsed.rows());
        transitInventoryService.syncChannelsForManualMaintenance();
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveCampaignSnapshot(CampaignSnapshotRows parsed) {
        campaignSnapshotRepository.deactivateAllCurrentVersions();
        campaignItemSnapshotRepository.deactivateAllCurrentVersions();
        campaignSnapshotRepository.saveAll(parsed.campaigns());
        campaignItemSnapshotRepository.saveAll(parsed.items());
        return new DocumentImportTool.ImportOutcome(
                parsed.sourceRowCount(),
                parsed.campaigns().size() + parsed.items().size()
        );
    }
}
