package com.fordring.commandhistory;

import com.fordring.command.CommandExecutionRepository;
import com.fordring.command.CommandOutputChunkRepository;
import com.fordring.common.PageResult;
import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.common.enums.RiskLevel;
import com.fordring.config.FordringProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CommandHistoryQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final CommandExecutionRepository executionRepository;
    private final CommandOutputChunkRepository localOutputRepository;
    private final ImportedCommandHistoryRepository importedRepository;
    private final ImportedCommandOutputChunkRepository importedOutputRepository;
    private final FordringProperties properties;

    public CommandHistoryQueryService(JdbcTemplate jdbcTemplate,
                                      CommandExecutionRepository executionRepository,
                                      CommandOutputChunkRepository localOutputRepository,
                                      ImportedCommandHistoryRepository importedRepository,
                                      ImportedCommandOutputChunkRepository importedOutputRepository,
                                      FordringProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.executionRepository = executionRepository;
        this.localOutputRepository = localOutputRepository;
        this.importedRepository = importedRepository;
        this.importedOutputRepository = importedOutputRepository;
        this.properties = properties;
    }

    public PageResult<CommandHistoryItemDto> list(String keyword, String status, String origin,
                                                  String sourceEnvironmentId, Long importBatchId,
                                                  int page, int pageSize) {
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();
        var normalizedOrigin = origin == null || origin.isBlank() ? "ALL" : origin;
        if (!"ALL".equalsIgnoreCase(normalizedOrigin)) {
            filters.add("origin = ?");
            args.add(normalizedOrigin.toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            filters.add("LOWER(command) LIKE LOWER(?)");
            args.add("%" + keyword + "%");
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            filters.add("status = ?");
            args.add(status.toUpperCase());
        }
        if (sourceEnvironmentId != null && !sourceEnvironmentId.isBlank()) {
            filters.add("original_source_environment_id = ?");
            args.add(sourceEnvironmentId);
        }
        if (importBatchId != null) {
            filters.add("import_batch_id = ?");
            args.add(importBatchId);
        }
        var where = filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters);
        var baseSql = """
                SELECT * FROM (
                  SELECT
                    'local:' || id AS history_id,
                    'LOCAL' AS origin,
                    command,
                    target_id,
                    target_snapshot,
                    ? AS original_source_environment_id,
                    ? AS original_source_environment_name,
                    ? AS direct_source_environment_id,
                    ? AS direct_source_environment_name,
                    status,
                    duration_ms,
                    source,
                    operator_name,
                    executed_at,
                    output_size_bytes,
                    output_truncated,
                    error_message,
                    risk_level,
                    risk_confirmed,
                    NULL::bigint AS import_batch_id,
                    NULL::timestamptz AS imported_at,
                    NULL::text AS provenance_chain
                  FROM command_execution
                  UNION ALL
                  SELECT
                    'imported:' || id AS history_id,
                    'IMPORTED' AS origin,
                    command,
                    local_target_id AS target_id,
                    target_snapshot,
                    original_source_environment_id,
                    original_source_environment_name,
                    direct_source_environment_id,
                    direct_source_environment_name,
                    status,
                    duration_ms,
                    source,
                    operator_name,
                    executed_at,
                    output_size_bytes,
                    output_truncated,
                    error_message,
                    risk_level,
                    risk_confirmed,
                    import_batch_id,
                    imported_at,
                    provenance_chain
                  FROM imported_command_history
                ) history
                """;
        var baseArgs = List.of(properties.instance.id, properties.instance.name, properties.instance.id, properties.instance.name);
        var countArgs = new ArrayList<Object>(baseArgs);
        countArgs.addAll(args);
        var total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + baseSql + where + ") counted",
                Long.class, countArgs.toArray());

        var queryArgs = new ArrayList<Object>(baseArgs);
        queryArgs.addAll(args);
        queryArgs.add(pageSize);
        queryArgs.add(Math.max(page - 1, 0) * pageSize);
        var items = jdbcTemplate.query(baseSql + where + " ORDER BY executed_at DESC, history_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapItem(rs), queryArgs.toArray());
        return new PageResult<>(items, page, pageSize, total == null ? 0 : total);
    }

    public CommandHistoryItemDto get(String historyId) {
        var parsed = parseHistoryId(historyId);
        if ("local".equals(parsed.prefix())) {
            var execution = executionRepository.findById(parsed.id())
                    .orElseThrow(() -> new IllegalArgumentException("命令记录不存在"));
            return new CommandHistoryItemDto(
                    historyId, "LOCAL", execution.command, execution.targetId, execution.targetSnapshot,
                    properties.instance.id, properties.instance.name, properties.instance.id, properties.instance.name,
                    execution.status, execution.durationMs, execution.source, execution.operatorName,
                    execution.executedAt, execution.outputSizeBytes, execution.outputTruncated,
                    execution.errorMessage, execution.riskLevel, execution.riskConfirmed,
                    null, null, null
            );
        }
        var imported = importedRepository.findById(parsed.id())
                .orElseThrow(() -> new IllegalArgumentException("导入命令记录不存在"));
        return new CommandHistoryItemDto(
                historyId, "IMPORTED", imported.command, imported.localTargetId, imported.targetSnapshot,
                imported.originalSourceEnvironmentId, imported.originalSourceEnvironmentName,
                imported.directSourceEnvironmentId, imported.directSourceEnvironmentName,
                imported.status, imported.durationMs, imported.source, imported.operatorName,
                imported.executedAt, imported.outputSizeBytes, imported.outputTruncated,
                imported.errorMessage, imported.riskLevel, imported.riskConfirmed,
                imported.importBatchId, imported.importedAt, imported.provenanceChain
        );
    }

    public OutputResult output(String historyId, int fromSequence, int limit) {
        var parsed = parseHistoryId(historyId);
        if ("local".equals(parsed.prefix())) {
            var chunks = localOutputRepository.findByExecutionIdAndSequenceGreaterThanEqualOrderBySequenceAsc(parsed.id(), fromSequence)
                    .stream()
                    .limit(limit)
                    .map(chunk -> new CommandHistoryOutputChunkDto(chunk.sequence, chunk.content, chunk.sizeBytes, chunk.createdAt))
                    .toList();
            var execution = executionRepository.findById(parsed.id()).orElseThrow();
            var next = chunks.isEmpty() ? fromSequence : chunks.get(chunks.size() - 1).sequence() + 1;
            return new OutputResult(historyId, chunks, next, execution.outputTruncated);
        }
        var chunks = importedOutputRepository.findByImportedHistoryIdAndSequenceGreaterThanEqualOrderBySequenceAsc(parsed.id(), fromSequence)
                .stream()
                .limit(limit)
                .map(chunk -> new CommandHistoryOutputChunkDto(chunk.sequence, chunk.content, chunk.sizeBytes, chunk.createdAt))
                .toList();
        var imported = importedRepository.findById(parsed.id()).orElseThrow();
        var next = chunks.isEmpty() ? fromSequence : chunks.get(chunks.size() - 1).sequence() + 1;
        return new OutputResult(historyId, chunks, next, imported.outputTruncated);
    }

    private CommandHistoryItemDto mapItem(ResultSet rs) throws SQLException {
        return new CommandHistoryItemDto(
                rs.getString("history_id"),
                rs.getString("origin"),
                rs.getString("command"),
                getLong(rs, "target_id"),
                rs.getString("target_snapshot"),
                rs.getString("original_source_environment_id"),
                rs.getString("original_source_environment_name"),
                rs.getString("direct_source_environment_id"),
                rs.getString("direct_source_environment_name"),
                CommandStatus.valueOf(rs.getString("status")),
                getLong(rs, "duration_ms"),
                CommandSource.valueOf(rs.getString("source")),
                rs.getString("operator_name"),
                rs.getTimestamp("executed_at").toInstant(),
                getLong(rs, "output_size_bytes"),
                rs.getBoolean("output_truncated"),
                rs.getString("error_message"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getBoolean("risk_confirmed"),
                getLong(rs, "import_batch_id"),
                rs.getTimestamp("imported_at") == null ? null : rs.getTimestamp("imported_at").toInstant(),
                rs.getString("provenance_chain")
        );
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        var value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static ParsedHistoryId parseHistoryId(String historyId) {
        if (historyId == null || !historyId.contains(":")) {
            throw new IllegalArgumentException("命令历史 ID 不合法");
        }
        var parts = historyId.split(":", 2);
        if (!"local".equals(parts[0]) && !"imported".equals(parts[0])) {
            throw new IllegalArgumentException("命令历史 ID 不合法");
        }
        try {
            return new ParsedHistoryId(parts[0], Long.parseLong(parts[1]));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("命令历史 ID 不合法");
        }
    }

    record ParsedHistoryId(String prefix, Long id) {
    }

    public record OutputResult(String historyId, List<CommandHistoryOutputChunkDto> chunks, int nextSequence,
                               boolean outputTruncated) {
    }
}
