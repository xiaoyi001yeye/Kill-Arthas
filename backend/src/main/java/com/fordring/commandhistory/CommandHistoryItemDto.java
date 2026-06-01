package com.fordring.commandhistory;

import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;

import java.time.Instant;

public record CommandHistoryItemDto(
        String historyId,
        String origin,
        String command,
        Long targetId,
        String targetSnapshot,
        String originalSourceEnvironmentId,
        String originalSourceEnvironmentName,
        String directSourceEnvironmentId,
        String directSourceEnvironmentName,
        CommandStatus status,
        Long durationMs,
        CommandSource source,
        String operatorName,
        Instant executedAt,
        Long outputSizeBytes,
        Boolean outputTruncated,
        String errorMessage,
        RiskLevel riskLevel,
        Boolean riskConfirmed,
        Long importBatchId,
        Instant importedAt,
        String provenanceChain
) {
}
