package com.fordring.target;

import com.fordring.audit.AuditService;
import com.fordring.common.PageResult;
import com.fordring.common.enums.ArthasStatus;
import com.fordring.common.enums.TargetType;
import com.fordring.config.FordringProperties;
import com.fordring.credential.CredentialService;
import com.fordring.arthas.ArthasInstallationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AccessTargetService {
    private final AccessTargetRepository repository;
    private final CredentialService credentialService;
    private final AuditService auditService;
    private final FordringProperties properties;
    private final ArthasInstallationService arthasInstallationService;

    public AccessTargetService(AccessTargetRepository repository, CredentialService credentialService,
                               AuditService auditService, FordringProperties properties,
                               ArthasInstallationService arthasInstallationService) {
        this.repository = repository;
        this.credentialService = credentialService;
        this.auditService = auditService;
        this.properties = properties;
        this.arthasInstallationService = arthasInstallationService;
    }

    public record CredentialInput(String name, String secret) {
    }

    public record CreateAccessTargetRequest(String name, String environment, String host, Integer sshPort,
                                            com.fordring.common.enums.AuthType authType, String username,
                                            CredentialInput credential,
                                            com.fordring.common.enums.TargetType targetType,
                                            String containerName, Long processId, String processName,
                                            Integer telnetPort, Integer httpPort) {
    }

    public record Stats(long totalCount, long attachedCount, long pendingCount, long failedCount) {
    }

    public Stats stats() {
        var total = repository.count();
        var attached = repository.countByArthasStatus(ArthasStatus.ATTACHED);
        var failed = repository.countByArthasStatus(ArthasStatus.CHECK_FAILED)
                + repository.countByArthasStatus(ArthasStatus.ATTACH_FAILED);
        return new Stats(total, attached, Math.max(0, total - attached), failed);
    }

    public PageResult<AccessTargetDto> list(String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(Math.max(page - 1, 0), pageSize);
        var result = keyword == null || keyword.isBlank()
                ? repository.findAll(pageable)
                : repository.findByNameContainingIgnoreCaseOrHostContainingIgnoreCaseOrProcessNameContainingIgnoreCase(
                keyword, keyword, keyword, pageable);
        return new PageResult<>(result.map(AccessTargetDto::from).toList(), page, pageSize, result.getTotalElements());
    }

    @Transactional
    public AccessTargetDto create(CreateAccessTargetRequest request, String operatorName) {
        var now = Instant.now();
        var target = new AccessTarget();
        target.name = required(request.name(), "接入名称不能为空");
        target.environment = request.environment();
        target.host = required(request.host(), "主机地址不能为空");
        target.sshPort = normalizeSshPort(request.sshPort());
        target.authType = request.authType();
        target.username = required(request.username(), "用户名不能为空");
        if (request.credential() != null) {
            target.credentialId = credentialService.save(request.credential().name(), request.authType(), request.credential().secret());
        }
        validateTarget(request);
        target.targetType = request.targetType();
        target.containerName = request.containerName();
        target.processId = request.processId();
        target.processName = request.processName();
        target.arthasStatus = ArthasStatus.NOT_ATTACHED;
        target.telnetPort = request.telnetPort() == null ? properties.arthas.defaultTelnetPort : request.telnetPort();
        target.httpPort = request.httpPort() == null ? properties.arthas.defaultHttpPort : request.httpPort();
        target.createdByName = operatorName;
        target.createdAt = now;
        target.updatedAt = now;
        target.latestOperationTime = now;
        var saved = repository.save(target);
        auditService.record("TARGET_CREATE", "ACCESS_TARGET", saved.id, operatorName, "SUCCESS", null, null, null);
        return AccessTargetDto.from(saved);
    }

    @Transactional
    public AccessTargetDto update(Long id, CreateAccessTargetRequest request, String operatorName) {
        var target = get(id);
        var oldConnectionSensitiveValue = target.host + "|" + target.sshPort + "|" + target.containerName + "|" + target.processId + "|" + target.processName;
        target.name = required(request.name(), "接入名称不能为空");
        target.environment = request.environment();
        target.host = required(request.host(), "主机地址不能为空");
        target.sshPort = normalizeSshPort(request.sshPort());
        target.authType = request.authType();
        target.username = required(request.username(), "用户名不能为空");
        if (request.credential() != null && request.credential().secret() != null && !request.credential().secret().isBlank()) {
            target.credentialId = credentialService.save(request.credential().name(), request.authType(), request.credential().secret());
        }
        validateTarget(request);
        target.targetType = request.targetType();
        target.containerName = request.containerName();
        target.processId = request.processId();
        target.processName = request.processName();
        target.telnetPort = request.telnetPort() == null ? target.telnetPort : request.telnetPort();
        target.httpPort = request.httpPort() == null ? target.httpPort : request.httpPort();
        target.updatedAt = Instant.now();
        target.latestOperationTime = Instant.now();
        var newConnectionSensitiveValue = target.host + "|" + target.sshPort + "|" + target.containerName + "|" + target.processId + "|" + target.processName;
        if (!oldConnectionSensitiveValue.equals(newConnectionSensitiveValue)) {
            target.arthasStatus = ArthasStatus.NOT_ATTACHED;
            target.latestFailureReason = null;
        }
        var saved = repository.save(target);
        auditService.record("TARGET_UPDATE", "ACCESS_TARGET", saved.id, operatorName, "SUCCESS", null, null, null);
        return AccessTargetDto.from(saved);
    }

    @Transactional
    public void delete(Long id, String operatorName) {
        var target = get(id);
        if (target.arthasStatus == ArthasStatus.ATTACHED) {
            throw new IllegalArgumentException("已接入目标请先断开 Arthas 后再删除");
        }
        repository.delete(target);
        auditService.record("TARGET_DELETE", "ACCESS_TARGET", id, operatorName, "SUCCESS", null, null, null);
    }

    @Transactional
    public AccessTargetDto check(Long id, String operatorName) {
        var target = get(id);
        target.arthasStatus = ArthasStatus.NOT_ATTACHED;
        target.latestOperationTime = Instant.now();
        target.latestFailureReason = null;
        auditService.record("TARGET_CHECK", "ACCESS_TARGET", id, operatorName, "SUCCESS", null, null, null);
        return AccessTargetDto.from(repository.save(target));
    }

    @Transactional
    public AccessTargetDto attach(Long id, Integer telnetPort, Integer httpPort, Boolean forceRestart, String operatorName) {
        var target = get(id);
        target.telnetPort = telnetPort == null ? target.telnetPort : telnetPort;
        target.httpPort = httpPort == null ? target.httpPort : httpPort;
        try {
            arthasInstallationService.attach(target, Boolean.TRUE.equals(forceRestart), operatorName);
            target.arthasStatus = ArthasStatus.ATTACHED;
            target.latestOperationTime = Instant.now();
            target.latestFailureReason = null;
            auditService.record("TARGET_ATTACH", "ACCESS_TARGET", id, operatorName, "SUCCESS", null, null, null);
            return AccessTargetDto.from(repository.save(target));
        } catch (RuntimeException error) {
            target.arthasStatus = ArthasStatus.ATTACH_FAILED;
            target.latestOperationTime = Instant.now();
            target.latestFailureReason = error.getMessage();
            repository.save(target);
            auditService.record("TARGET_ATTACH", "ACCESS_TARGET", id, operatorName, "FAILED", error.getMessage(), null, null);
            throw error;
        }
    }

    @Transactional
    public void markArthasDisconnected(Long id, String reason) {
        var target = get(id);
        if (target.arthasStatus != ArthasStatus.ATTACHED) {
            return;
        }
        target.arthasStatus = ArthasStatus.DISCONNECTED;
        target.latestOperationTime = Instant.now();
        target.latestFailureReason = reason;
        repository.save(target);
        auditService.record("TARGET_ARTHAS_DISCONNECTED", "ACCESS_TARGET", id, "system", "SUCCESS", reason, null, null);
    }

    @Transactional
    public AccessTargetDto detach(Long id, String operatorName) {
        var target = get(id);
        try {
            arthasInstallationService.detach(target, operatorName);
            target.arthasStatus = ArthasStatus.DISCONNECTED;
            target.latestOperationTime = Instant.now();
            target.latestFailureReason = null;
            auditService.record("TARGET_DETACH", "ACCESS_TARGET", id, operatorName, "SUCCESS", null, null, null);
            return AccessTargetDto.from(repository.save(target));
        } catch (RuntimeException error) {
            target.latestOperationTime = Instant.now();
            target.latestFailureReason = error.getMessage();
            repository.save(target);
            auditService.record("TARGET_DETACH", "ACCESS_TARGET", id, operatorName, "FAILED", error.getMessage(), null, null);
            throw error;
        }
    }

    public AccessTarget get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("接入目标不存在"));
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private int normalizeSshPort(Integer port) {
        var value = port == null ? properties.access.defaultSshPort : port;
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("SSH 端口必须在 1-65535 之间");
        }
        return value;
    }

    private static void validateTarget(CreateAccessTargetRequest request) {
        if (request.targetType() == null) {
            throw new IllegalArgumentException("目标类型不能为空");
        }
        if (request.targetType() == TargetType.DOCKER_CONTAINER && (request.containerName() == null || request.containerName().isBlank())) {
            throw new IllegalArgumentException("Docker 容器目标必须填写容器名称");
        }
        if (request.processId() == null) {
            throw new IllegalArgumentException("Java 进程 PID 不能为空");
        }
    }
}
