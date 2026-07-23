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
    private Long id;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)", unique = true)
    private String dataFingerprint;

    @Column(length = 80)
    private String provider;

    @Column(length = 120)
    private String modelName;

    @Column(nullable = false, columnDefinition = "json")
    private String requestPayload;

    @Column(columnDefinition = "json")
    private String responsePayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiAnalysisStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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
