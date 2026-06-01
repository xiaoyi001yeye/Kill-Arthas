package com.fordring.commandhistory;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "command_history_import_batch")
public class CommandHistoryImportBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String fileName;
    public String schemaVersion;
    public String exportId;
    public String exportingEnvironmentId;
    public String exportingEnvironmentName;
    public String exportingBaseUrl;
    public Instant exportedAt;
    public String exportedBy;
    public Integer recordCount;
    public Integer importedCount = 0;
    public Integer skippedDuplicateCount = 0;
    public Integer failedCount = 0;
    public String packageChecksum;
    public String status;

    @Column(columnDefinition = "text")
    public String failureReason;

    public String importedBy;
    public Instant importedAt;
}
