package com.fordring.commandhistory;

import java.time.Instant;

public record CommandHistoryOutputChunkDto(Integer sequence, String content, Integer sizeBytes, Instant createdAt) {
}
