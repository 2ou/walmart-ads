package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.DemandForecastRecord;
import com.ai.walmartads.importing.domain.DomesticInventorySnapshot;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.ai.walmartads.importing.domain.ItemReportRecord;
import com.ai.walmartads.importing.domain.OrderRecord;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import com.ai.walmartads.importing.domain.ReturnOrderRecord;
import com.ai.walmartads.importing.domain.SkuMapping;
import com.ai.walmartads.importing.domain.ShipmentLogisticsRecord;
import com.ai.walmartads.importing.domain.ShipmentRecord;
import com.ai.walmartads.importing.parser.AdCsvParser;
import com.ai.walmartads.importing.parser.DemandForecastParser;
import com.ai.walmartads.importing.parser.DomesticInventoryWorkbookParser;
import com.ai.walmartads.importing.parser.HistoricalWorkbookParser;
import com.ai.walmartads.importing.parser.HistoricalWorkbookRows;
import com.ai.walmartads.importing.parser.InventoryWorkbookParser;
import com.ai.walmartads.importing.parser.ItemReportCsvParser;
import com.ai.walmartads.importing.parser.OrderWorkbookParser;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.parser.ProductWorkbookParser;
import com.ai.walmartads.importing.parser.ProductionOrderParser;
import com.ai.walmartads.importing.parser.ReturnOrderWorkbookParser;
import com.ai.walmartads.importing.parser.SkuMappingWorkbookParser;
import com.ai.walmartads.importing.parser.ShipmentLogisticsWorkbookParser;
import com.ai.walmartads.importing.parser.ShipmentWorkbookParser;
import com.ai.walmartads.importing.parser.WeeklyInventoryWorkbookParser;
import com.ai.walmartads.importing.repository.AdPerformanceRepository;
import com.ai.walmartads.importing.repository.DemandForecastRepository;
import com.ai.walmartads.importing.repository.DomesticInventorySnapshotRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.ItemReportRepository;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FileImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(FileImportProcessor.class);

    private final ImportFileRepository fileRepository;
    private final OrderRecordRepository orderRepository;
    private final AdPerformanceRepository adRepository;
    private final InventorySnapshotRepository inventoryRepository;
    private final ProductRecordRepository productRepository;
    private final SkuMappingRepository mappingRepository;
    private final ReturnOrderRecordRepository returnOrderRepository;
    private final ItemReportRepository itemReportRepository;
    private final OrderWorkbookParser orderParser;
    private final AdCsvParser adParser;
    private final InventoryWorkbookParser inventoryParser;
    private final WeeklyInventoryWorkbookParser weeklyInventoryParser;
    private final ProductWorkbookParser productParser;
    private final HistoricalWorkbookParser historicalParser;
    private final SkuMappingWorkbookParser mappingParser;
    private final ReturnOrderWorkbookParser returnOrderParser;
    private final ItemReportCsvParser itemReportParser;
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
            InventorySnapshotRepository inventoryRepository,
            ProductRecordRepository productRepository,
            SkuMappingRepository mappingRepository,
            ReturnOrderRecordRepository returnOrderRepository,
            ItemReportRepository itemReportRepository,
            OrderWorkbookParser orderParser,
            AdCsvParser adParser,
            InventoryWorkbookParser inventoryParser,
            WeeklyInventoryWorkbookParser weeklyInventoryParser,
            ProductWorkbookParser productParser,
            HistoricalWorkbookParser historicalParser,
            SkuMappingWorkbookParser mappingParser,
            ReturnOrderWorkbookParser returnOrderParser,
            ItemReportCsvParser itemReportParser,
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
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.mappingRepository = mappingRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.itemReportRepository = itemReportRepository;
        this.orderParser = orderParser;
        this.adParser = adParser;
        this.inventoryParser = inventoryParser;
        this.weeklyInventoryParser = weeklyInventoryParser;
        this.productParser = productParser;
        this.historicalParser = historicalParser;
        this.mappingParser = mappingParser;
        this.returnOrderParser = returnOrderParser;
        this.itemReportParser = itemReportParser;
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
                (path, file) -> saveHistory(historicalParser.parse(path, file)),
                ImportFileType.HISTORY
        );
        register(
                "legacy",
                (path, file) -> saveSkuMappings(mappingParser.parse(path, file)),
                ImportFileType.SKU_MAPPING
        );
        register(
                "2",
                (path, file) -> saveReturnOrders(returnOrderParser.parse(path, file)),
                ImportFileType.RETURN_ORDER
        );
        register(
                "1",
                (path, file) -> saveItemReports(itemReportParser.parse(path, file)),
                ImportFileType.ITEM_REPORT
        );
        register(
                "1",
                (path, file) -> saveDemandForecasts(demandForecastParser.parse(path, file)),
                ImportFileType.DEMAND_FORECAST
        );
        register(
                "1",
                (path, file) -> saveDomesticInventory(domesticInventoryParser.parse(path, file)),
                ImportFileType.DOMESTIC_INVENTORY
        );
        register(
                "3",
                (path, file) -> saveProductionOrders(productionOrderParser.parse(path, file)),
                ImportFileType.PRODUCTION_ORDER
        );
        register(
                "1",
                (path, file) -> saveShipments(shipmentParser.parse(path, file)),
                ImportFileType.SHIPMENT
        );
        register(
                "1",
                (path, file) -> saveShipmentLogistics(shipmentLogisticsParser.parse(path, file)),
                ImportFileType.SHIPMENT_LOGISTICS
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
        } else if (file.getReportDate() != null && file.getFileType() != ImportFileType.PRODUCT
                && file.getFileType() != ImportFileType.INVENTORY
                && file.getFileType() != ImportFileType.ITEM_REPORT) {
            fileRepository.deactivateReportVersion(file.getFileType(), file.getReportDate(), file.getId());
        } else if (file.getFileType() == ImportFileType.HISTORY
                || file.getFileType() == ImportFileType.SKU_MAPPING
                || file.getFileType() == ImportFileType.PRODUCTION_ORDER
                || file.getFileType() == ImportFileType.SHIPMENT
                || file.getFileType() == ImportFileType.SHIPMENT_LOGISTICS) {
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
        if (!skus.isEmpty()) {
            productRepository.deactivateCurrentVersions(skus);
        }
        productRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveHistory(HistoricalWorkbookRows parsed) {
        orderRepository.saveAll(parsed.orders());
        adRepository.saveAll(parsed.ads());
        return new DocumentImportTool.ImportOutcome(
                parsed.sourceRowCount(),
                parsed.orders().size() + parsed.ads().size()
        );
    }

    private DocumentImportTool.ImportOutcome saveSkuMappings(ParsedRows<SkuMapping> parsed) {
        mappingRepository.deactivateCurrentVersions();
        mappingRepository.saveAllAndFlush(parsed.rows());
        adRepository.backfillSkuFromCurrentMappings();
        orderRepository.backfillUpcFromCurrentMappings();
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveReturnOrders(ParsedRows<ReturnOrderRecord> parsed) {
        returnOrderRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveItemReports(ParsedRows<ItemReportRecord> parsed) {
        List<String> skus = parsed.rows().stream()
                .map(ItemReportRecord::getSku)
                .distinct()
                .toList();
        if (!skus.isEmpty()) {
            itemReportRepository.deactivateCurrentVersions(skus);
        }
        itemReportRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
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

    private DocumentImportTool.ImportOutcome saveProductionOrders(ParsedRows<ProductionOrderRecord> parsed) {
        // 整类型作废：重新导入时先作废全部旧版本，仅保留本次导入（对应「只放一个文件、以最新扫描为准」）
        productionOrderRepository.deactivateAllCurrentVersions();
        productionOrderRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveShipments(ParsedRows<ShipmentRecord> parsed) {
        shipmentRepository.deactivateAllCurrentVersions();
        shipmentRepository.saveAll(parsed.rows());
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }

    private DocumentImportTool.ImportOutcome saveShipmentLogistics(ParsedRows<ShipmentLogisticsRecord> parsed) {
        shipmentLogisticsRepository.deactivateAllCurrentVersions();
        shipmentLogisticsRepository.saveAll(parsed.rows());
        transitInventoryService.syncChannelsForManualMaintenance();
        return new DocumentImportTool.ImportOutcome(parsed.sourceRowCount(), parsed.rows().size());
    }
}
