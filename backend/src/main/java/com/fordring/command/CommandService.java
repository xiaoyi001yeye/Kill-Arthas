package com.fordring.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fordring.audit.AuditService;
import com.fordring.common.PageResult;
import com.fordring.common.enums.*;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTarget;
import com.fordring.target.AccessTargetService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class CommandService {
    private final CommandExecutionRepository executionRepository;
    private final CommandOutputChunkRepository outputRepository;
    private final AccessTargetService targetService;
    private final RiskService riskService;
    private final AuditService auditService;
    private final FordringProperties properties;
    private final ObjectMapper objectMapper;

    public CommandService(CommandExecutionRepository executionRepository, CommandOutputChunkRepository outputRepository,
                          AccessTargetService targetService, RiskService riskService, AuditService auditService,
                          FordringProperties properties, ObjectMapper objectMapper) {
        this.executionRepository = executionRepository;
        this.outputRepository = outputRepository;
        this.targetService = targetService;
        this.riskService = riskService;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record ExecuteRequest(Long targetId, String command, Integer timeoutSeconds, CommandSource source,
                                 Boolean riskConfirmed, Boolean keepHistory) {
    }

    public record ExecutionStarted(Long executionId, Long targetId, CommandStatus status, Instant startedAt) {
    }

    @Transactional
    public CommandExecution createExecution(ExecuteRequest request, String operatorName) {
        var target = targetService.get(request.targetId());
        if (target.arthasStatus != ArthasStatus.ATTACHED) {
            throw new IllegalArgumentException("目标未接入 Arthas");
        }
        var risk = riskService.check(request.command());
        if (risk.riskLevel() == RiskLevel.DENY) {
            auditService.record("COMMAND_DENY", "ACCESS_TARGET", target.id, operatorName, "FAILED",
                    risk.message(), risk.riskLevel(), false);
            throw new IllegalArgumentException(risk.message());
        }
        if (risk.riskLevel() == RiskLevel.CONFIRM && !Boolean.TRUE.equals(request.riskConfirmed())) {
            throw new IllegalArgumentException("该命令需要二次确认");
        }
        var now = Instant.now();
        var execution = new CommandExecution();
        execution.command = request.command();
        execution.targetId = target.id;
        execution.targetSnapshot = snapshot(target);
        execution.status = CommandStatus.RUNNING;
        execution.source = request.source() == null ? CommandSource.MANUAL : request.source();
        execution.operatorName = operatorName;
        execution.executedAt = now;
        execution.riskLevel = risk.riskLevel();
        execution.riskConfirmed = Boolean.TRUE.equals(request.riskConfirmed());
        var saved = executionRepository.save(execution);
        auditService.record("COMMAND_EXECUTE", "COMMAND_EXECUTION", saved.id, operatorName, "SUCCESS",
                null, saved.riskLevel, saved.riskConfirmed);
        return saved;
    }

    @Transactional
    public ExecutionStarted executeSync(ExecuteRequest request, String operatorName) {
        var execution = createExecution(request, operatorName);
        return new ExecutionStarted(execution.id, request.targetId(), CommandStatus.RUNNING, execution.executedAt);
    }

    public PageResult<CommandExecutionDto> list(String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(Math.max(page - 1, 0), pageSize);
        var result = keyword == null || keyword.isBlank()
                ? executionRepository.findAll(pageable)
                : executionRepository.findByCommandContainingIgnoreCase(keyword, pageable);
        return new PageResult<>(result.map(CommandExecutionDto::from).toList(), page, pageSize, result.getTotalElements());
    }

    public CommandExecutionDto get(Long id) {
        return CommandExecutionDto.from(executionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("命令记录不存在")));
    }

    public OutputResult output(Long id, int fromSequence, int limit) {
        var chunks = outputRepository.findByExecutionIdAndSequenceGreaterThanEqualOrderBySequenceAsc(id, fromSequence)
                .stream()
                .limit(limit)
                .map(OutputChunkDto::from)
                .toList();
        var next = chunks.isEmpty() ? fromSequence : chunks.get(chunks.size() - 1).sequence() + 1;
        var execution = executionRepository.findById(id).orElseThrow();
        return new OutputResult(id, chunks, next, execution.outputTruncated);
    }

    @Transactional
    public CommandExecution rerun(Long id, ExecuteRequest override, String operatorName) {
        var origin = executionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("命令记录不存在"));
        return createExecution(new ExecuteRequest(
                override.targetId() == null ? origin.targetId : override.targetId(),
                override.command() == null ? origin.command : override.command(),
                override.timeoutSeconds(), CommandSource.RERUN, override.riskConfirmed(), true
        ), operatorName);
    }

    @Transactional
    public void appendOutput(Long executionId, String content) {
        var execution = executionRepository.findById(executionId).orElseThrow();
        if (Boolean.TRUE.equals(execution.outputTruncated)) {
            return;
        }
        var bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (execution.outputSizeBytes + bytes > properties.command.outputMaxBytes) {
            execution.outputTruncated = true;
            executionRepository.save(execution);
            return;
        }
        var chunk = new CommandOutputChunk();
        chunk.executionId = executionId;
        chunk.sequence = outputRepository.countByExecutionId(executionId) + 1;
        chunk.content = content;
        chunk.sizeBytes = bytes;
        chunk.createdAt = Instant.now();
        outputRepository.save(chunk);
        execution.outputSizeBytes += bytes;
        executionRepository.save(execution);
    }

    @Transactional
    public CommandExecution finish(Long executionId, CommandStatus status, String errorMessage) {
        var execution = executionRepository.findById(executionId).orElseThrow();
        execution.status = status;
        execution.errorMessage = errorMessage;
        execution.durationMs = Duration.between(execution.executedAt, Instant.now()).toMillis();
        return executionRepository.save(execution);
    }

    private String snapshot(AccessTarget target) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "name", target.name,
                    "host", target.host,
                    "targetType", target.targetType.name(),
                    "containerName", target.containerName == null ? "" : target.containerName,
                    "processId", target.processId == null ? 0 : target.processId,
                    "processName", target.processName == null ? "" : target.processName
            ));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public record OutputResult(Long executionId, List<OutputChunkDto> chunks, int nextSequence, boolean outputTruncated) {
    }
}
