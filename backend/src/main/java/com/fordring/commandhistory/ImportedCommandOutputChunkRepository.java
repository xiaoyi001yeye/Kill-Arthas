package com.fordring.commandhistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportedCommandOutputChunkRepository extends JpaRepository<ImportedCommandOutputChunk, Long> {
    List<ImportedCommandOutputChunk> findByImportedHistoryIdAndSequenceGreaterThanEqualOrderBySequenceAsc(Long importedHistoryId, Integer sequence);
}
