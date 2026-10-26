package com.ai.walmartads.wfs;

import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wfs")
public class WfsController {

    private final WfsService service;

    public WfsController(WfsService service) {
        this.service = service;
    }

    @PostMapping(value = "/settlements/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<WfsService.ImportResult> importSettlement(@RequestParam("file") MultipartFile file) throws IOException {
        return importFile(WfsReportType.SETTLEMENT, file);
    }

    @PostMapping(value = "/storage/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<WfsService.ImportResult> importStorage(@RequestParam("file") MultipartFile file) throws IOException {
        return importFile(WfsReportType.STORAGE, file);
    }

    @GetMapping("/settlements")
    public ApiResponse<WfsService.PageResult> settlements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionTo,
            @RequestParam(required = false) String type, @RequestParam(required = false) String reason,
            @RequestParam(required = false) String match, @RequestParam(required = false) BigDecimal feeMin,
            @RequestParam(required = false) BigDecimal feeMax, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "DESC") String direction) {
        return ApiResponse.ok(service.settlements(new WfsService.Query(periodStart, periodEnd, transactionFrom,
                transactionTo, type, reason, match, feeMin, feeMax, search, page, size, sort, direction)));
    }

    @GetMapping("/storage")
    public ApiResponse<WfsService.PageResult> storage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String match, @RequestParam(required = false) BigDecimal feeMin,
            @RequestParam(required = false) BigDecimal feeMax, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "DESC") String direction) {
        return ApiResponse.ok(service.storage(new WfsService.Query(periodStart, periodEnd, null, null,
                null, null, match, feeMin, feeMax, search, page, size, sort, direction)));
    }

    @GetMapping("/settlements/{id}")
    public ApiResponse<Map<String, Object>> settlementDetail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(WfsReportType.SETTLEMENT, id));
    }

    @GetMapping("/storage/{id}")
    public ApiResponse<Map<String, Object>> storageDetail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(WfsReportType.STORAGE, id));
    }

    @GetMapping("/imports")
    public ApiResponse<List<WfsService.BatchView>> imports(@RequestParam WfsReportType type,
                                                           @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(service.batches(type, limit));
    }

    @DeleteMapping("/imports/{batchId}")
    public ApiResponse<WfsService.DeleteResult> deleteImport(@PathVariable long batchId) {
        return ApiResponse.ok("导入批次已删除", service.deleteBatch(batchId));
    }

    private ApiResponse<WfsService.ImportResult> importFile(WfsReportType type, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的 WFS 报表");
        WfsService.ImportResult result = service.importReport(type, file.getOriginalFilename(), file.getBytes());
        return result.status().equals("FAILED")
                ? new ApiResponse<>(false, result.message(), result)
                : ApiResponse.ok(result.message(), result);
    }
}
