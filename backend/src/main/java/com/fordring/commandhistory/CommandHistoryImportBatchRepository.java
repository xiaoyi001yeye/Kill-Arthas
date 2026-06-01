package com.fordring.commandhistory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandHistoryImportBatchRepository extends JpaRepository<CommandHistoryImportBatch, Long> {
}
