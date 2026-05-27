package com.fordring.target;

import com.fordring.common.enums.ArthasStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessTargetRepository extends JpaRepository<AccessTarget, Long> {
    long countByArthasStatus(ArthasStatus status);

    Page<AccessTarget> findByNameContainingIgnoreCaseOrHostContainingIgnoreCaseOrProcessNameContainingIgnoreCase(
            String name, String host, String processName, Pageable pageable);
}

