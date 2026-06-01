package com.fordring.commandhistory;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "imported_command_output_chunk")
public class ImportedCommandOutputChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long importedHistoryId;
    public Integer sequence;

    @Column(columnDefinition = "text")
    public String content;

    public Integer sizeBytes;
    public Instant createdAt;
}
