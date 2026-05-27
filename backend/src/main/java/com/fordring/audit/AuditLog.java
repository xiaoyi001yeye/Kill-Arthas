package com.fordring.audit;

import com.fordring.common.enums.RiskLevel;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String action;
    public String resourceType;
    public String resourceId;
    public String operatorName;

    @Column(columnDefinition = "text")
    public String requestPayload;

    public String result;
    public String failureReason;

    @Enumerated(EnumType.STRING)
    public RiskLevel riskLevel;

    public Boolean riskConfirmed;
    public Instant createdAt;
}
