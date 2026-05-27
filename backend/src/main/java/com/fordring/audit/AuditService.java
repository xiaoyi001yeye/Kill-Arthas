package com.fordring.audit;

import com.fordring.common.enums.RiskLevel;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String resourceType, Object resourceId, String operatorName,
                       String result, String failureReason, RiskLevel riskLevel, Boolean riskConfirmed) {
        var log = new AuditLog();
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId == null ? null : resourceId.toString();
        log.operatorName = operatorName;
        log.requestPayload = "{}";
        log.result = result;
        log.failureReason = failureReason;
        log.riskLevel = riskLevel;
        log.riskConfirmed = riskConfirmed;
        log.createdAt = Instant.now();
        repository.save(log);
    }
}

