package com.fordring.target;

import com.fordring.common.enums.ArthasStatus;
import com.fordring.common.enums.AuthType;
import com.fordring.common.enums.TargetType;

import java.time.Instant;

public record AccessTargetDto(
        Long id,
        String name,
        String environment,
        String host,
        Integer sshPort,
        AuthType authType,
        String username,
        Long credentialId,
        TargetType targetType,
        String containerName,
        Long processId,
        String processName,
        ArthasStatus arthasStatus,
        Integer telnetPort,
        Integer httpPort,
        Instant latestOperationTime,
        String latestFailureReason,
        String createdByName,
        Instant createdAt
) {
    public static AccessTargetDto from(AccessTarget target) {
        return new AccessTargetDto(target.id, target.name, target.environment, target.host, target.sshPort,
                target.authType, target.username, target.credentialId, target.targetType, target.containerName, target.processId,
                target.processName, target.arthasStatus, target.telnetPort, target.httpPort,
                target.latestOperationTime, target.latestFailureReason, target.createdByName, target.createdAt);
    }
}
