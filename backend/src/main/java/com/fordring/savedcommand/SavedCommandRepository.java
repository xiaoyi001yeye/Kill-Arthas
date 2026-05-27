package com.fordring.savedcommand;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedCommandRepository extends JpaRepository<SavedCommand, Long> {
    List<SavedCommand> findByVisibleInConsole(Boolean visibleInConsole);
}

