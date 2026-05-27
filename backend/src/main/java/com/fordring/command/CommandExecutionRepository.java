package com.fordring.command;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandExecutionRepository extends JpaRepository<CommandExecution, Long> {
    Page<CommandExecution> findByCommandContainingIgnoreCase(String command, Pageable pageable);
}

