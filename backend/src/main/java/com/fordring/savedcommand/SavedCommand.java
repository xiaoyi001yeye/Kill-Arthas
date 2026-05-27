package com.fordring.savedcommand;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "saved_command")
public class SavedCommand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;

    @Column(columnDefinition = "text")
    public String command;

    @Column(columnDefinition = "text")
    public String description;

    public Boolean visibleInConsole;
    public String operatorName;
    public Instant createdAt;
    public Instant updatedAt;
}

