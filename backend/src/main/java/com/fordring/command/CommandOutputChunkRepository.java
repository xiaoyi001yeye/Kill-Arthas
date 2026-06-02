package com.fordring.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface CommandOutputChunkRepository extends JpaRepository<CommandOutputChunk, Long> {
    List<CommandOutputChunk> findByExecutionIdAndSequenceGreaterThanEqualOrderBySequenceAsc(Long executionId, Integer sequence);

    int countByExecutionId(Long executionId);

    void deleteByExecutionIdIn(Collection<Long> executionIds);
}
