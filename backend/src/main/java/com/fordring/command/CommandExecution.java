package com.fordring.command;

import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "command_execution")
public class CommandExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(columnDefinition = "text")
    public String command;

    public Long targetId;

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

    @Column(columnDefinition = "text")
    public String errorMessage;

    @Enumerated(EnumType.STRING)
    public RiskLevel riskLevel = RiskLevel.ALLOW;

    public Boolean riskConfirmed = false;
}
