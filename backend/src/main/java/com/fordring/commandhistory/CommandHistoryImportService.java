package com.fordring.commandhistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fordring.audit.AuditService;
import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;
import com.fordring.config.FordringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

import static com.fordring.commandhistory.CommandHistoryArchiveCodec.*;

@Service
public class CommandHistoryImportService {
    private static final Logger log = LoggerFactory.getLogger(CommandHistoryImportService.class);
    private final CommandHistoryImportBatchRepository batchRepository;
    private final ImportedCommandHistoryRepository importedRepository;
    private final ImportedCommandOutputChunkRepository outputRepository;
    private final FordringProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Map<String, PreviewToken> previewTokens = new ConcurrentHashMap<>();

    public CommandHistoryImportService(CommandHistoryImportBatchRepository batchRepository,
                                       ImportedCommandHistoryRepository importedRepository,
                                       ImportedCommandOutputChunkRepository outputRepository,
                                       FordringProperties properties,
                                       ObjectMapper objectMapper,
                                       AuditService auditService) {
        this.batchRepository = batchRepository;
        this.importedRepository = importedRepository;
        this.outputRepository = outputRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public ImportPreview preview(MultipartFile file, String operatorName) {
        log.info("Command history import preview requested operator={} originalFilename={} sizeBytes={}",
                operatorName, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getSize());
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 zip 文件");
        }
        if (file.getSize() > properties.commandHistory.importMaxBytes) {
            throw new IllegalArgumentException("导入文件超过大小限制");
        }
        try {
            cleanupExpiredTokens();
            var token = "tmp-" + UUID.randomUUID();
            var temp = Files.createTempFile("fordring-command-history-", ".zip");
            try (var in = file.getInputStream()) {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var parsed = parseArchive(temp);
            log.info("Command history import preview parsed token={} exportId={} recordCount={} outputSizeBytes={}",
                    token, parsed.manifest().path("exportId").asText(), parsed.records().size(), parsed.outputSizeBytes());
            var duplicateCount = 0;
            for (var record : parsed.records()) {
                if (importedRepository.existsByDuplicateKey(duplicateKey(record))) {
                    duplicateCount++;
                }
            }
            var expiresAt = Instant.now().plusSeconds(Math.max(properties.commandHistory.importPreviewTtlMinutes, 1) * 60L);
            previewTokens.put(token, new PreviewToken(temp, operatorName, expiresAt, file.getOriginalFilename()));
            auditService.record("COMMAND_HISTORY_IMPORT_PREVIEW", "COMMAND_HISTORY_IMPORT", token, operatorName,
                    "SUCCESS", null, null, null);
            log.info("Command history import preview finished token={} duplicateCount={} expiresAt={}",
                    token, duplicateCount, expiresAt);
            return new ImportPreview(
                    token,
                    parsed.manifest().path("schemaVersion").asText(),
                    parsed.manifest().path("exportingEnvironment").path("name").asText(),
                    parsed.manifest().path("exportingEnvironment").path("instanceId").asText(),
                    Instant.parse(parsed.manifest().path("exportedAt").asText()),
                    parsed.manifest().path("exportedBy").asText(),
                    parsed.records().size(),
                    parsed.outputSizeBytes(),
                    duplicateCount,
                    0,
                    List.of("导出包包含命令输出，请确认来源可信并符合数据流转规范")
            );
        } catch (IllegalArgumentException error) {
            log.warn("Command history import preview rejected operator={} originalFilename={} message={}",
                    operatorName, file == null ? null : file.getOriginalFilename(), error.getMessage());
            auditService.record("COMMAND_HISTORY_IMPORT_PREVIEW", "COMMAND_HISTORY_IMPORT", null, operatorName,
                    "FAILED", error.getMessage(), null, null);
            throw error;
        } catch (Exception error) {
            log.error("Command history import preview failed operator={} originalFilename={}",
                    operatorName, file == null ? null : file.getOriginalFilename(), error);
            auditService.record("COMMAND_HISTORY_IMPORT_PREVIEW", "COMMAND_HISTORY_IMPORT", null, operatorName,
                    "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("导入预览失败：" + error.getMessage(), error);
        }
    }

    @Transactional
    public ImportResult importArchive(ImportConfirmRequest request, String operatorName) {
        log.info("Command history import confirm requested operator={} token={}",
                operatorName, request == null ? null : request.fileToken());
        var token = previewTokens.get(request.fileToken());
        if (token == null || token.expiresAt().isBefore(Instant.now()) || !token.operatorName().equals(operatorName)) {
            log.warn("Command history import confirm rejected invalid token operator={} token={}",
                    operatorName, request == null ? null : request.fileToken());
            throw new IllegalArgumentException("导入文件已过期，请重新上传");
        }
        try {
            var parsed = parseArchive(token.path());
            log.info("Command history import confirm parsed token={} exportId={} recordCount={}",
                    request.fileToken(), parsed.manifest().path("exportId").asText(), parsed.records().size());
            var now = Instant.now();
            var manifest = parsed.manifest();
            var exporting = manifest.path("exportingEnvironment");
            var batch = new CommandHistoryImportBatch();
            batch.fileName = token.originalFilename() == null ? token.path().getFileName().toString() : token.originalFilename();
            batch.schemaVersion = manifest.path("schemaVersion").asText();
            batch.exportId = manifest.path("exportId").asText();
            batch.exportingEnvironmentId = exporting.path("instanceId").asText();
            batch.exportingEnvironmentName = exporting.path("name").asText();
            batch.exportingBaseUrl = exporting.path("baseUrl").asText("");
            batch.exportedAt = Instant.parse(manifest.path("exportedAt").asText());
            batch.exportedBy = manifest.path("exportedBy").asText();
            batch.recordCount = parsed.records().size();
            batch.packageChecksum = sha256(parsed.checksumBytes());
            batch.status = "SUCCESS";
            batch.importedBy = operatorName;
            batch.importedAt = now;
            batch = batchRepository.save(batch);
            log.info("Command history import batch created batchId={} exportId={} exportingEnvironment={} recordCount={}",
                    batch.id, batch.exportId, batch.exportingEnvironmentId, batch.recordCount);

            var importedCount = 0;
            var skippedCount = 0;
            for (var record : parsed.records()) {
                var duplicateKey = duplicateKey(record);
                if (importedRepository.existsByDuplicateKey(duplicateKey)) {
                    log.info("Command history import skipping duplicate batchId={} duplicateKey={} originExecutionId={}",
                            batch.id, duplicateKey, record.path("originExecutionId").asLong());
                    skippedCount++;
                    continue;
                }
                var outputNode = record.path("output");
                var outputBytes = parsed.files().get(outputNode.path("path").asText());
                var imported = new ImportedCommandHistory();
                imported.importBatchId = batch.id;
                imported.originalSourceEnvironmentId = record.path("originalSourceEnvironment").path("instanceId").asText();
                imported.originalSourceEnvironmentName = record.path("originalSourceEnvironment").path("name").asText();
                imported.directSourceEnvironmentId = batch.exportingEnvironmentId;
                imported.directSourceEnvironmentName = batch.exportingEnvironmentName;
                imported.originExecutionId = record.path("originExecutionId").asLong();
                imported.command = record.path("command").asText();
                imported.targetSnapshot = utf8(record.path("targetSnapshot"), objectMapper);
                imported.status = CommandStatus.valueOf(record.path("status").asText());
                imported.durationMs = record.path("durationMs").isNull() ? null : record.path("durationMs").asLong();
                imported.source = CommandSource.valueOf(record.path("source").asText());
                imported.operatorName = record.path("operatorName").asText();
                imported.executedAt = Instant.parse(record.path("executedAt").asText());
                imported.outputSizeBytes = outputNode.path("sizeBytes").asLong();
                imported.outputTruncated = record.path("outputTruncated").asBoolean(false);
                imported.outputSha256 = outputNode.path("sha256").asText();
                imported.errorMessage = record.path("errorMessage").isNull() ? null : record.path("errorMessage").asText();
                imported.riskLevel = RiskLevel.valueOf(record.path("riskLevel").asText());
                imported.riskConfirmed = record.path("riskConfirmed").asBoolean(false);
                imported.recordChecksum = record.path("recordChecksum").asText();
                imported.duplicateKey = duplicateKey;
                imported.provenanceChain = provenanceWithImport(record.path("provenanceChain"), batch.id, now);
                imported.importedBy = operatorName;
                imported.importedAt = now;
                var saved = importedRepository.save(imported);
                saveOutputChunks(saved.id, outputBytes, now);
                log.info("Command history import record saved batchId={} importedHistoryId={} originExecutionId={} outputBytes={} commandPreview={}",
                        batch.id, saved.id, imported.originExecutionId, outputBytes.length, preview(imported.command));
                importedCount++;
            }
            batch.importedCount = importedCount;
            batch.skippedDuplicateCount = skippedCount;
            batchRepository.save(batch);
            auditService.record("COMMAND_HISTORY_IMPORT", "COMMAND_HISTORY_IMPORT", batch.id, operatorName,
                    "SUCCESS", null, null, null);
            log.info("Command history import finished batchId={} importedCount={} skippedDuplicateCount={}",
                    batch.id, importedCount, skippedCount);
            return new ImportResult(batch.id, importedCount, skippedCount, 0);
        } catch (IllegalArgumentException error) {
            log.warn("Command history import rejected operator={} token={} message={}",
                    operatorName, request.fileToken(), error.getMessage());
            auditService.record("COMMAND_HISTORY_IMPORT", "COMMAND_HISTORY_IMPORT", null, operatorName,
                    "FAILED", error.getMessage(), null, null);
            throw error;
        } catch (Exception error) {
            log.error("Command history import failed operator={} token={}", operatorName, request.fileToken(), error);
            auditService.record("COMMAND_HISTORY_IMPORT", "COMMAND_HISTORY_IMPORT", null, operatorName,
                    "FAILED", error.getMessage(), null, null);
            throw error;
        } finally {
            previewTokens.remove(request.fileToken());
            try {
                Files.deleteIfExists(token.path());
            } catch (Exception ignored) {
            }
        }
    }

    private ParsedArchive parseArchive(Path path) {
        try {
            var files = readZip(path);
            log.info("Command history import archive files read path={} fileCount={}", path, files.size());
            var manifestBytes = requiredFile(files, "manifest.json");
            var recordsBytes = requiredFile(files, "records.ndjson");
            var checksumsBytes = requiredFile(files, "checksums.sha256");
            validateChecksums(files, new String(checksumsBytes, StandardCharsets.UTF_8));
            var manifest = objectMapper.readTree(manifestBytes);
            if (!"FORDRING_COMMAND_HISTORY_EXPORT".equals(manifest.path("fileType").asText())) {
                throw new IllegalArgumentException("不是 Fordring 命令历史导出包");
            }
            if (!"1.0".equals(manifest.path("schemaVersion").asText())) {
                throw new IllegalArgumentException("不支持的导出包版本：" + manifest.path("schemaVersion").asText());
            }
            var exportingEnvironmentId = manifest.path("exportingEnvironment").path("instanceId").asText();
            if (exportingEnvironmentId == null || exportingEnvironmentId.isBlank()) {
                throw new IllegalArgumentException("导出包缺少导出环境实例 ID");
            }
            var records = parseRecords(recordsBytes, files);
            var outputSize = records.stream()
                    .mapToLong(record -> record.path("output").path("sizeBytes").asLong())
                    .sum();
            log.info("Command history import archive parsed exportId={} recordCount={} outputSizeBytes={}",
                    manifest.path("exportId").asText(), records.size(), outputSize);
            return new ParsedArchive((ObjectNode) manifest, records, files, checksumsBytes, outputSize);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("解析导入 zip 失败：" + error.getMessage(), error);
        }
    }

    private Map<String, byte[]> readZip(Path path) throws Exception {
        var files = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(path))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                var name = entry.getName();
                validateEntryName(name);
                if (!entry.isDirectory()) {
                    if (files.size() >= properties.commandHistory.importMaxEntryCount) {
                        throw new IllegalArgumentException("zip 文件条目数量超过限制");
                    }
                    var out = new ByteArrayOutputStream();
                    zip.transferTo(out);
                    var bytes = out.toByteArray();
                    total += bytes.length;
                    if (total > properties.commandHistory.importMaxUncompressedBytes) {
                        log.warn("Command history import zip rejected by uncompressed size path={} totalBytes={} maxBytes={}",
                                path, total, properties.commandHistory.importMaxUncompressedBytes);
                        throw new IllegalArgumentException("zip 解压后大小超过限制");
                    }
                    log.debug("Command history import zip entry path={} bytes={}", name, bytes.length);
                    files.put(name, bytes);
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
        return files;
    }

    private void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
            throw new IllegalArgumentException("zip 包含非法路径：" + name);
        }
        var parts = name.split("/");
        for (String part : parts) {
            if (part.equals("..") || part.isBlank()) {
                throw new IllegalArgumentException("zip 包含非法路径：" + name);
            }
        }
    }

    private byte[] requiredFile(Map<String, byte[]> files, String path) {
        var bytes = files.get(path);
        if (bytes == null) {
            throw new IllegalArgumentException("zip 缺少必需文件：" + path);
        }
        return bytes;
    }

    private void validateChecksums(Map<String, byte[]> files, String checksumText) {
        var lines = checksumText.split("\\R");
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var parts = line.trim().split("\\s+", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("checksums.sha256 格式不合法");
            }
            var expected = parts[0];
            var path = parts[1].trim();
            var bytes = files.get(path);
            if (bytes == null) {
                throw new IllegalArgumentException("校验清单引用了不存在的文件：" + path);
            }
            if (!expected.equals(sha256(bytes))) {
                throw new IllegalArgumentException("文件校验失败：" + path);
            }
        }
    }

    private List<ObjectNode> parseRecords(byte[] recordsBytes, Map<String, byte[]> files) throws Exception {
        var records = new ArrayList<ObjectNode>();
        var lines = new String(recordsBytes, StandardCharsets.UTF_8).split("\\R");
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var node = objectMapper.readTree(line);
            if (!node.isObject()) {
                throw new IllegalArgumentException("records.ndjson 包含非对象记录");
            }
            var record = (ObjectNode) node;
            validateRecord(record, files);
            records.add(record);
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("导出包中没有命令记录");
        }
        return records;
    }

    private void validateRecord(ObjectNode record, Map<String, byte[]> files) {
        requireText(record, "command");
        requireText(record, "status");
        requireText(record, "source");
        requireText(record, "operatorName");
        requireText(record, "executedAt");
        requireText(record, "riskLevel");
        requireText(record, "recordChecksum");
        var originalId = record.path("originalSourceEnvironment").path("instanceId").asText();
        if (originalId == null || originalId.isBlank()) {
            throw new IllegalArgumentException("记录缺少原始来源环境实例 ID");
        }
        if (!record.has("originExecutionId")) {
            throw new IllegalArgumentException("记录缺少原始执行 ID");
        }
        CommandStatus.valueOf(record.path("status").asText());
        CommandSource.valueOf(record.path("source").asText());
        RiskLevel.valueOf(record.path("riskLevel").asText());
        Instant.parse(record.path("executedAt").asText());
        if (!record.path("targetSnapshot").isObject()) {
            throw new IllegalArgumentException("记录 targetSnapshot 必须是对象");
        }
        if (!record.path("provenanceChain").isArray()) {
            throw new IllegalArgumentException("记录缺少来源链");
        }
        var output = record.path("output");
        var outputPath = output.path("path").asText();
        validateEntryName(outputPath);
        var outputBytes = files.get(outputPath);
        if (outputBytes == null) {
            throw new IllegalArgumentException("记录缺少输出文件：" + outputPath);
        }
        if (output.path("sizeBytes").asLong(-1) != outputBytes.length) {
            throw new IllegalArgumentException("输出文件大小不匹配：" + outputPath);
        }
        if (!output.path("sha256").asText().equals(sha256(outputBytes))) {
            throw new IllegalArgumentException("输出文件校验失败：" + outputPath);
        }
        var expectedRecordChecksum = canonicalChecksum(objectMapper, recordFacts(objectMapper, record));
        if (!record.path("recordChecksum").asText().equals(expectedRecordChecksum)) {
            log.warn("Command history import record checksum mismatch originExecutionId={} expected={} actual={}",
                    record.path("originExecutionId").asText(), expectedRecordChecksum, record.path("recordChecksum").asText());
            throw new IllegalArgumentException("记录校验失败：" + record.path("originExecutionId").asText());
        }
    }

    private void requireText(ObjectNode record, String field) {
        if (!record.has(field) || record.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("记录缺少字段：" + field);
        }
    }

    private String duplicateKey(ObjectNode record) {
        return record.path("originalSourceEnvironment").path("instanceId").asText()
                + ":" + record.path("originExecutionId").asLong()
                + ":" + record.path("recordChecksum").asText();
    }

    private String provenanceWithImport(JsonNode provenance, Long batchId, Instant importedAt) {
        var chain = objectMapper.createArrayNode();
        provenance.forEach(chain::add);
        var event = objectMapper.createObjectNode();
        event.put("type", "IMPORTED");
        event.put("environmentId", properties.instance.id);
        event.put("environmentName", properties.instance.name);
        event.put("importBatchId", batchId);
        event.put("at", importedAt.toString());
        chain.add(event);
        return utf8(chain, objectMapper);
    }

    private void saveOutputChunks(Long importedHistoryId, byte[] outputBytes, Instant now) {
        var content = new String(outputBytes, StandardCharsets.UTF_8);
        var maxBytes = Math.max(properties.command.outputChunkBytes, 1);
        var sequence = 1;
        var builder = new StringBuilder();
        var size = 0;
        for (int i = 0; i < content.length(); ) {
            var codePoint = content.codePointAt(i);
            var chars = Character.toChars(codePoint);
            var charText = new String(chars);
            var charSize = charText.getBytes(StandardCharsets.UTF_8).length;
            if (size > 0 && size + charSize > maxBytes) {
                saveChunk(importedHistoryId, sequence++, builder.toString(), size, now);
                builder.setLength(0);
                size = 0;
            }
            builder.append(charText);
            size += charSize;
            i += chars.length;
        }
        if (builder.length() > 0 || outputBytes.length == 0) {
            saveChunk(importedHistoryId, sequence, builder.toString(), size, now);
        }
    }

    private void saveChunk(Long importedHistoryId, int sequence, String content, int size, Instant now) {
        var chunk = new ImportedCommandOutputChunk();
        chunk.importedHistoryId = importedHistoryId;
        chunk.sequence = sequence;
        chunk.content = content;
        chunk.sizeBytes = size;
        chunk.createdAt = now;
        outputRepository.save(chunk);
    }

    private String preview(String command) {
        if (command == null) {
            return "";
        }
        var normalized = command.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private void cleanupExpiredTokens() {
        var now = Instant.now();
        previewTokens.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt().isAfter(now)) {
                return false;
            }
            try {
                Files.deleteIfExists(entry.getValue().path());
            } catch (Exception ignored) {
            }
            return true;
        });
    }

    public record ImportPreview(String fileToken, String schemaVersion, String exportingEnvironmentName,
                                String exportingEnvironmentId, Instant exportedAt, String exportedBy,
                                int recordCount, long outputSizeBytes, int duplicateCount, int invalidCount,
                                List<String> warnings) {
    }

    public record ImportConfirmRequest(String fileToken) {
    }

    public record ImportResult(Long importBatchId, int importedCount, int skippedDuplicateCount, int failedCount) {
    }

    private record PreviewToken(Path path, String operatorName, Instant expiresAt, String originalFilename) {
    }

    private record ParsedArchive(ObjectNode manifest, List<ObjectNode> records, Map<String, byte[]> files,
                                 byte[] checksumBytes, long outputSizeBytes) {
    }
}
