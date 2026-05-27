package com.fordring.command;

import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;

import java.time.Instant;

public record CommandExecutionDto(
        Long id,
        String command,
        Long targetId,
        String targetSnapshot,
        CommandStatus status,
        Long durationMs,
        CommandSource source,
        String operatorName,
        Instant executedAt,
        Long outputSizeBytes,
        Boolean outputTruncated,
        String errorMessage,
        RiskLevel riskLevel,
        Boolean riskConfirmed
) {
    static CommandExecutionDto from(CommandExecution execution) {
        return new CommandExecutionDto(execution.id, execution.command, execution.targetId, execution.targetSnapshot,
                execution.status, execution.durationMs, execution.source, execution.operatorName,
                execution.executedAt, execution.outputSizeBytes, execution.outputTruncated,
                execution.errorMessage, execution.riskLevel, execution.riskConfirmed);
    }
}

