package com.fordring.savedcommand;

import java.time.Instant;

public record SavedCommandDto(Long id, String name, String command, String description,
                              Boolean visibleInConsole, String operatorName, Instant createdAt, Instant updatedAt) {
    static SavedCommandDto from(SavedCommand savedCommand) {
        return new SavedCommandDto(savedCommand.id, savedCommand.name, savedCommand.command,
                savedCommand.description, savedCommand.visibleInConsole, savedCommand.operatorName,
                savedCommand.createdAt, savedCommand.updatedAt);
    }
}

