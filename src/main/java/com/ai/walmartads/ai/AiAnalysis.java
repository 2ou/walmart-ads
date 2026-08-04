package com.ai.walmartads.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_ai_analysis")
public class AiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)", unique = true)
    /** 数据指纹（sha256），用于去重 */
    private String dataFingerprint;

    @Column(length = 80)
    /** AI 服务商 */
    private String provider;

    @Column(length = 120)
    /** 模型名称 */
    private String modelName;

    @Column(nullable = false, columnDefinition = "json")
    /** 请求报文（JSON） */
    private String requestPayload;

    @Column(columnDefinition = "json")
    /** 响应报文（JSON，可空） */
    private String responsePayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    /** 状态（如 pending/success/failed） */
    private AiAnalysisStatus status;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间（可空） */
    private LocalDateTime completedAt;

    protected AiAnalysis() {
    }

    public AiAnalysis(String dataFingerprint, String provider, String modelName, String requestPayload) {
        this.dataFingerprint = dataFingerprint;
        restart(provider, modelName, requestPayload);
    }

    public void restart(String provider, String modelName, String requestPayload) {
        this.provider = provider;
        this.modelName = modelName;
        this.requestPayload = requestPayload;
        this.responsePayload = null;
        this.status = AiAnalysisStatus.RUNNING;
        this.createdAt = LocalDateTime.now();
        this.completedAt = null;
    }

    public void succeed(String provider, String modelName, String responsePayload) {
        this.provider = provider;
        this.modelName = modelName;
        this.responsePayload = responsePayload;
        this.status = AiAnalysisStatus.SUCCESS;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String responsePayload) {
        this.responsePayload = responsePayload;
        this.status = AiAnalysisStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getDataFingerprint() {
        return dataFingerprint;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public AiAnalysisStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}
