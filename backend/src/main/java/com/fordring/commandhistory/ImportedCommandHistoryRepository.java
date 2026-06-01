package com.fordring.commandhistory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedCommandHistoryRepository extends JpaRepository<ImportedCommandHistory, Long> {
    boolean existsByDuplicateKey(String duplicateKey);
}
