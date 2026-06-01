package com.fordring.commandhistory;

import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "imported_command_history")
public class ImportedCommandHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long importBatchId;
    public String originalSourceEnvironmentId;
    public String originalSourceEnvironmentName;
    public String directSourceEnvironmentId;
    public String directSourceEnvironmentName;
    public Long originExecutionId;

    @Column(columnDefinition = "text")
    public String command;

    @Column(columnDefinition = "text")
    public String targetSnapshot;

    @Enumerated(EnumType.STRING)
    public CommandStatus status;

    public Long durationMs;

    @Enumerated(EnumType.STRING)
    public CommandSource source;

    public String operatorName;
    public Instant executedAt;
    public Long outputSizeBytes = 0L;
    public Boolean outputTruncated = false;
    public String outputSha256;

    @Column(columnDefinition = "text")
    public String errorMessage;

    @Enumerated(EnumType.STRING)
    public RiskLevel riskLevel = RiskLevel.ALLOW;

    public Boolean riskConfirmed = false;
    public String recordChecksum;
    public String duplicateKey;

    @Column(columnDefinition = "text")
    public String provenanceChain;

    public Long localTargetId;
    public String importedBy;
    public Instant importedAt;
}
