package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.AdPerformance;
import com.ai.walmartads.importing.domain.ImportFile;
import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.ai.walmartads.importing.domain.OrderRecord;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.parser.AdCsvParser;
import com.ai.walmartads.importing.parser.InventoryWorkbookParser;
import com.ai.walmartads.importing.parser.OrderWorkbookParser;
import com.ai.walmartads.importing.parser.ParsedRows;
import com.ai.walmartads.importing.parser.ProductWorkbookParser;
import com.ai.walmartads.importing.repository.AdPerformanceRepository;
import com.ai.walmartads.importing.repository.ImportFileRepository;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.OrderRecordRepository;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class FileImportProcessor {

    private final ImportFileRepository fileRepository;
    private final OrderRecordRepository orderRepository;
    private final AdPerformanceRepository adRepository;
    private final InventorySnapshotRepository inventoryRepository;
    private final ProductRecordRepository productRepository;
    private final OrderWorkbookParser orderParser;
    private final AdCsvParser adParser;
    private final InventoryWorkbookParser inventoryParser;
    private final ProductWorkbookParser productParser;

    public FileImportProcessor(
            ImportFileRepository fileRepository,
            OrderRecordRepository orderRepository,
            AdPerformanceRepository adRepository,
            InventorySnapshotRepository inventoryRepository,
            ProductRecordRepository productRepository,
            OrderWorkbookParser orderParser,
            AdCsvParser adParser,
            InventoryWorkbookParser inventoryParser,
            ProductWorkbookParser productParser
    ) {
        this.fileRepository = fileRepository;
        this.orderRepository = orderRepository;
        this.adRepository = adRepository;
        this.inventoryRepository = inventoryRepository;
        this.productRepository = productRepository;
        this.orderParser = orderParser;
        this.adParser = adParser;
        this.inventoryParser = inventoryParser;
        this.productParser = productParser;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessedFile process(Long fileId, Path archivePath) throws IOException {
        ImportFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("导入文件记录不存在：" + fileId));
        ProcessedFile result = switch (file.getFileType()) {
            case ORDER -> saveOrders(orderParser.parse(archivePath, file));
            case AD_AUTO, AD_MANUAL -> saveAds(adParser.parse(archivePath, file));
            case INVENTORY -> saveInventory(inventoryParser.parse(archivePath, file));
            case PRODUCT -> saveProducts(productParser.parse(archivePath, file));
        };
        file.imported(archivePath.toString(), result.sourceRows(), result.importedRows());
        if (file.getReportDate() != null && file.getFileType() != ImportFileType.PRODUCT
                && file.getFileType() != ImportFileType.INVENTORY) {
            fileRepository.deactivateReportVersion(file.getFileType(), file.getReportDate(), file.getId());
        }
        return result;
    }

    private ProcessedFile saveOrders(ParsedRows<OrderRecord> parsed) {
        orderRepository.saveAll(parsed.rows());
        return new ProcessedFile(parsed.sourceRowCount(), parsed.rows().size());
    }

    private ProcessedFile saveAds(ParsedRows<AdPerformance> parsed) {
        adRepository.saveAll(parsed.rows());
        return new ProcessedFile(parsed.sourceRowCount(), parsed.rows().size());
    }

    private ProcessedFile saveInventory(ParsedRows<InventorySnapshot> parsed) {
        inventoryRepository.saveAll(parsed.rows());
        return new ProcessedFile(parsed.sourceRowCount(), parsed.rows().size());
    }

    private ProcessedFile saveProducts(ParsedRows<ProductRecord> parsed) {
        List<String> skus = parsed.rows().stream()
                .map(ProductRecord::getSku)
                .distinct()
                .toList();
        if (!skus.isEmpty()) {
            productRepository.deactivateCurrentVersions(skus);
        }
        productRepository.saveAll(parsed.rows());
        return new ProcessedFile(parsed.sourceRowCount(), parsed.rows().size());
    }

    public record ProcessedFile(int sourceRows, int importedRows) {
    }
}
