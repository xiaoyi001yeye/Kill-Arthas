package com.fordring.commandhistory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fordring.audit.AuditService;
import com.fordring.command.CommandExecution;
import com.fordring.command.CommandExecutionRepository;
import com.fordring.command.CommandOutputChunkRepository;
import com.fordring.common.enums.CommandStatus;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.fordring.commandhistory.CommandHistoryArchiveCodec.*;

@Service
public class CommandHistoryExportService {
    private static final Logger log = LoggerFactory.getLogger(CommandHistoryExportService.class);
    private static final Set<CommandStatus> EXPORTABLE_STATUSES = Set.of(
            CommandStatus.SUCCESS, CommandStatus.FAILED, CommandStatus.TIMEOUT, CommandStatus.STOPPED, CommandStatus.CANCELLED
    );

    private final CommandExecutionRepository executionRepository;
    private final CommandOutputChunkRepository localOutputRepository;
    private final ImportedCommandHistoryRepository importedRepository;
    private final ImportedCommandOutputChunkRepository importedOutputRepository;
    private final AccessTargetRepository targetRepository;
    private final FordringProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public CommandHistoryExportService(CommandExecutionRepository executionRepository,
                                       CommandOutputChunkRepository localOutputRepository,
                                       ImportedCommandHistoryRepository importedRepository,
                                       ImportedCommandOutputChunkRepository importedOutputRepository,
                                       AccessTargetRepository targetRepository,
                                       FordringProperties properties,
                                       ObjectMapper objectMapper,
                                       AuditService auditService) {
        this.executionRepository = executionRepository;
        this.localOutputRepository = localOutputRepository;
        this.importedRepository = importedRepository;
        this.importedOutputRepository = importedOutputRepository;
        this.targetRepository = targetRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public ExportedArchive export(ExportRequest request, String operatorName) {
        var requestedCount = request.historyIds() == null ? 0 : request.historyIds().size();
        log.info("Command history export requested operator={} requestedCount={} instanceId={}",
                operatorName, requestedCount, properties.instance.id);
        if (properties.instance.id == null || properties.instance.id.isBlank()) {
            throw new IllegalArgumentException("未配置稳定的 Fordring 实例 ID，不能导出");
        }
        if (request.historyIds() == null || request.historyIds().isEmpty()) {
            throw new IllegalArgumentException("请选择要导出的命令历史");
        }
        if (request.historyIds().size() > properties.commandHistory.exportMaxRecords) {
            throw new IllegalArgumentException("单次导出记录数超过限制");
        }

        var exportId = UUID.randomUUID().toString();
        var exportedAt = Instant.now();
        var records = new ArrayList<ObjectNode>();
        var outputFiles = new LinkedHashMap<String, byte[]>();
        for (String historyId : request.historyIds()) {
            var parsed = CommandHistoryQueryService.parseHistoryId(historyId);
            log.info("Command history export record loading exportId={} historyId={} origin={} id={}",
                    exportId, historyId, parsed.prefix(), parsed.id());
            if ("local".equals(parsed.prefix())) {
                records.add(localRecord(parsed.id(), exportId, exportedAt, outputFiles));
            } else {
                records.add(importedRecord(parsed.id(), exportId, exportedAt, outputFiles));
            }
        }

        var manifest = manifest(exportId, exportedAt, operatorName, records.size(), outputFiles.size());
        var files = new LinkedHashMap<String, byte[]>();
        files.put("manifest.json", utf8Bytes(utf8(manifest, objectMapper) + "\n"));
        var ndjson = new StringBuilder();
        for (var record : records) {
            ndjson.append(utf8(record, objectMapper)).append('\n');
        }
        files.put("records.ndjson", utf8Bytes(ndjson.toString()));
        files.putAll(outputFiles);
        files.put("checksums.sha256", utf8Bytes(checksums(files)));

        log.info("Command history export archive assembling exportId={} recordCount={} outputFileCount={} logicalFileCount={}",
                exportId, records.size(), outputFiles.size(), files.size());
        var zipBytes = zip(files);
        if (zipBytes.length > properties.commandHistory.exportMaxBytes) {
            log.warn("Command history export rejected by size exportId={} zipBytes={} maxBytes={}",
                    exportId, zipBytes.length, properties.commandHistory.exportMaxBytes);
            throw new IllegalArgumentException("导出文件超过大小限制");
        }

        auditService.record("COMMAND_HISTORY_EXPORT", "COMMAND_HISTORY_EXPORT", exportId, operatorName,
                "SUCCESS", null, null, null);
        var filename = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.systemDefault())
                .format(exportedAt) + "-命令导出.zip";
        log.info("Command history export finished exportId={} filename={} zipBytes={} recordCount={} outputFileCount={}",
                exportId, filename, zipBytes.length, records.size(), outputFiles.size());
        return new ExportedArchive(filename, zipBytes);
    }

    private ObjectNode localRecord(Long id, String exportId, Instant exportedAt, Map<String, byte[]> outputFiles) {
        var execution = executionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("命令记录不存在：" + id));
        ensureExportable(execution.status);
        log.info("Command history export local record exportId={} executionId={} targetId={} status={} outputSizeBytes={} commandPreview={}",
                exportId, id, execution.targetId, execution.status, execution.outputSizeBytes, preview(execution.command));
        var output = localOutputRepository.findByExecutionIdAndSequenceGreaterThanEqualOrderBySequenceAsc(id, 1)
                .stream()
                .map(chunk -> chunk.content)
                .reduce("", String::concat);
        var outputBytes = utf8Bytes(output);
        var outputPath = outputPath(properties.instance.id, id, null);
        outputFiles.put(outputPath, outputBytes);

        var record = baseRecord("LOCAL", properties.instance.id, properties.instance.name, execution.id,
                execution.command, structuredTargetSnapshot(execution), execution.status.name(), execution.durationMs,
                execution.source.name(), execution.operatorName, execution.executedAt, outputPath, outputBytes,
                execution.outputTruncated, execution.errorMessage, execution.riskLevel.name(), execution.riskConfirmed);
        var provenance = objectMapper.createArrayNode();
        provenance.add(event("EXECUTED", execution.executedAt, execution.id, null, null));
        provenance.add(event("EXPORTED", exportedAt, null, exportId, null));
        record.set("provenanceChain", provenance);
        record.put("recordChecksum", canonicalChecksum(objectMapper, recordFacts(objectMapper, record)));
        return record;
    }

    private ObjectNode importedRecord(Long id, String exportId, Instant exportedAt, Map<String, byte[]> outputFiles) {
        var imported = importedRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("导入命令记录不存在：" + id));
        ensureExportable(imported.status);
        log.info("Command history export imported record exportId={} importedHistoryId={} originalSource={} originExecutionId={} status={} outputSizeBytes={} commandPreview={}",
                exportId, id, imported.originalSourceEnvironmentId, imported.originExecutionId,
                imported.status, imported.outputSizeBytes, preview(imported.command));
        var output = importedOutputRepository.findByImportedHistoryIdAndSequenceGreaterThanEqualOrderBySequenceAsc(id, 1)
                .stream()
                .map(chunk -> chunk.content)
                .reduce("", String::concat);
        var outputBytes = utf8Bytes(output);
        var outputPath = outputPath(imported.originalSourceEnvironmentId, imported.originExecutionId, null);
        outputFiles.put(outputPath, outputBytes);

        var record = baseRecord("IMPORTED", imported.originalSourceEnvironmentId, imported.originalSourceEnvironmentName,
                imported.originExecutionId, imported.command, parseObject(imported.targetSnapshot), imported.status.name(),
                imported.durationMs, imported.source.name(), imported.operatorName, imported.executedAt, outputPath,
                outputBytes, imported.outputTruncated, imported.errorMessage, imported.riskLevel.name(), imported.riskConfirmed);
        var provenance = parseArray(imported.provenanceChain);
        provenance.add(event("EXPORTED", exportedAt, null, exportId, null));
        record.set("provenanceChain", provenance);
        record.put("recordChecksum", canonicalChecksum(objectMapper, recordFacts(objectMapper, record)));
        return record;
    }

    private ObjectNode baseRecord(String recordOrigin, String originalEnvironmentId, String originalEnvironmentName,
                                  Long originExecutionId, String command, ObjectNode targetSnapshot, String status,
                                  Long durationMs, String source, String operatorName, Instant executedAt,
                                  String outputPath, byte[] outputBytes, Boolean outputTruncated, String errorMessage,
                                  String riskLevel, Boolean riskConfirmed) {
        var record = objectMapper.createObjectNode();
        record.put("recordOrigin", recordOrigin);
        var original = objectMapper.createObjectNode();
        original.put("instanceId", originalEnvironmentId);
        original.put("name", originalEnvironmentName);
        record.set("originalSourceEnvironment", original);
        record.put("originExecutionId", originExecutionId);
        record.put("command", command);
        record.set("targetSnapshot", targetSnapshot);
        record.put("status", status);
        if (durationMs == null) {
            record.putNull("durationMs");
        } else {
            record.put("durationMs", durationMs);
        }
        record.put("source", source);
        record.put("operatorName", operatorName);
        record.put("executedAt", executedAt.toString());
        var output = objectMapper.createObjectNode();
        output.put("path", outputPath);
        output.put("sizeBytes", outputBytes.length);
        output.put("sha256", sha256(outputBytes));
        record.set("output", output);
        record.put("outputTruncated", Boolean.TRUE.equals(outputTruncated));
        if (errorMessage == null) {
            record.putNull("errorMessage");
        } else {
            record.put("errorMessage", errorMessage);
        }
        record.put("riskLevel", riskLevel);
        record.put("riskConfirmed", Boolean.TRUE.equals(riskConfirmed));
        return record;
    }

    private ObjectNode structuredTargetSnapshot(CommandExecution execution) {
        var snapshot = parseObject(execution.targetSnapshot);
        snapshot.put("sourceTargetId", execution.targetId);
        targetRepository.findById(execution.targetId).ifPresent(target -> {
            putIfMissing(snapshot, "name", target.name);
            putIfMissing(snapshot, "environment", target.environment);
            putIfMissing(snapshot, "host", target.host);
            if (!snapshot.has("sshPort") && target.sshPort != null) {
                snapshot.put("sshPort", target.sshPort);
            }
            putIfMissing(snapshot, "targetType", target.targetType == null ? null : target.targetType.name());
            putIfMissing(snapshot, "containerName", target.containerName);
            if (!snapshot.has("processId") && target.processId != null) {
                snapshot.put("processId", target.processId);
            }
            putIfMissing(snapshot, "processName", target.processName);
            if (!snapshot.has("telnetPort") && target.telnetPort != null) {
                snapshot.put("telnetPort", target.telnetPort);
            }
            if (!snapshot.has("httpPort") && target.httpPort != null) {
                snapshot.put("httpPort", target.httpPort);
            }
        });
        return snapshot;
    }

    private void putIfMissing(ObjectNode object, String field, String value) {
        if (!object.has(field) && value != null) {
            object.put(field, value);
        }
    }

    private ObjectNode manifest(String exportId, Instant exportedAt, String operatorName, int recordCount, int outputFileCount) {
        var manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", "1.0");
        manifest.put("fileType", "FORDRING_COMMAND_HISTORY_EXPORT");
        manifest.put("exportId", exportId);
        manifest.put("exportedAt", exportedAt.toString());
        manifest.put("exportedBy", operatorName);
        var environment = objectMapper.createObjectNode();
        environment.put("instanceId", properties.instance.id);
        environment.put("name", properties.instance.name);
        environment.put("baseUrl", properties.instance.baseUrl == null ? "" : properties.instance.baseUrl);
        manifest.set("exportingEnvironment", environment);
        var options = objectMapper.createObjectNode();
        options.put("outputIncluded", true);
        options.put("outputEncoding", "utf-8");
        manifest.set("options", options);
        manifest.put("recordCount", recordCount);
        manifest.put("outputFileCount", outputFileCount);
        return manifest;
    }

    private ObjectNode event(String type, Instant at, Long executionId, String exportId, Long importBatchId) {
        var event = objectMapper.createObjectNode();
        event.put("type", type);
        event.put("environmentId", properties.instance.id);
        event.put("environmentName", properties.instance.name);
        if (executionId != null) {
            event.put("executionId", executionId);
        }
        if (exportId != null) {
            event.put("exportId", exportId);
        }
        if (importBatchId != null) {
            event.put("importBatchId", importBatchId);
        }
        event.put("at", at.toString());
        return event;
    }

    private ObjectNode parseObject(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }
            var node = objectMapper.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private ArrayNode parseArray(String json) {
        try {
            var node = objectMapper.readTree(json);
            return node != null && node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
        } catch (Exception error) {
            return objectMapper.createArrayNode();
        }
    }

    private String outputPath(String environmentId, Long executionId, String checksum) {
        var path = "outputs/" + safePathPart(environmentId) + "-" + executionId;
        if (checksum != null && checksum.length() > 16) {
            path += "-" + checksum.substring(checksum.length() - 12);
        }
        return path + ".txt";
    }

    private String checksums(LinkedHashMap<String, byte[]> files) {
        var builder = new StringBuilder();
        files.forEach((path, content) -> builder.append(sha256(content)).append("  ").append(path).append('\n'));
        return builder.toString();
    }

    private byte[] zip(LinkedHashMap<String, byte[]> files) {
        try {
            var out = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(out)) {
                for (var entry : files.entrySet()) {
                    log.debug("Command history export zip entry path={} bytes={}", entry.getKey(), entry.getValue().length);
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalArgumentException("生成导出 zip 失败", error);
        }
    }

    private void ensureExportable(CommandStatus status) {
        if (!EXPORTABLE_STATUSES.contains(status)) {
            log.warn("Command history export rejected non-terminal status={}", status);
            throw new IllegalArgumentException("只能导出已结束的命令记录，当前状态：" + status);
        }
    }

    private String preview(String command) {
        if (command == null) {
            return "";
        }
        var normalized = command.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    public record ExportRequest(List<String> historyIds) {
    }

    public record ExportedArchive(String filename, byte[] content) {
    }
}
