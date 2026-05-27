package com.fordring.command;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "command_output_chunk")
public class CommandOutputChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long executionId;
    public Integer sequence;

    @Column(columnDefinition = "text")
    public String content;

    public Integer sizeBytes;
    public Instant createdAt;
}

