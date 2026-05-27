package com.fordring.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommandOutputChunkRepository extends JpaRepository<CommandOutputChunk, Long> {
    List<CommandOutputChunk> findByExecutionIdAndSequenceGreaterThanEqualOrderBySequenceAsc(Long executionId, Integer sequence);

    int countByExecutionId(Long executionId);
}

