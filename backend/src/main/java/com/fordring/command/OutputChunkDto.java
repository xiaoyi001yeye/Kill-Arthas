package com.fordring.command;

import java.time.Instant;

public record OutputChunkDto(Integer sequence, String content, Integer sizeBytes, Instant createdAt) {
    static OutputChunkDto from(CommandOutputChunk chunk) {
        return new OutputChunkDto(chunk.sequence, chunk.content, chunk.sizeBytes, chunk.createdAt);
    }
}

