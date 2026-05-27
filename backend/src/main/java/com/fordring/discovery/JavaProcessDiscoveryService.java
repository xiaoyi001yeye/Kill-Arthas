package com.fordring.discovery;

import com.fordring.common.enums.AuthType;
import com.fordring.common.enums.TargetType;
import com.fordring.config.FordringProperties;
import com.fordring.credential.CredentialService;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class JavaProcessDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(JavaProcessDiscoveryService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final String PROCESS_DISCOVERY_SCRIPT = """
            if command -v jps >/dev/null 2>&1; then
              jps -lv
            else
              ps -eo pid,user,args | grep '[j]ava'
            fi
            """;

    private final CredentialService credentialService;
    private final FordringProperties properties;

    public JavaProcessDiscoveryService(CredentialService credentialService, FordringProperties properties) {
        this.credentialService = credentialService;
        this.properties = properties;
    }

    public DiscoveryController.JavaProcessDiscoveryResult discover(DiscoveryController.DiscoveryRequest request) {
        var traceId = "java-pid-" + UUID.randomUUID().toString().substring(0, 8);
        var host = required(request.host(), "主机地址不能为空");
        var username = required(request.username(), "用户名不能为空");
        var authType = request.authType() == null ? AuthType.PASSWORD : request.authType();
        var targetType = request.targetType() == null ? TargetType.PHYSICAL_JAVA : request.targetType();
        var sshPort = normalizeSshPort(request.sshPort());

        try {
            log.info("Java PID discovery started traceId={} host={} sshPort={} username={} authType={} targetType={} containerName={} credentialSource={}",
                    traceId, host, sshPort, username, authType, targetType, safeValue(request.containerName()), credentialSource(request));

            var secret = resolveSecret(request);
            var command = buildDiscoveryCommand(targetType, request.containerName());
            log.info("Java PID discovery command prepared traceId={} strategy={} commandTimeoutSeconds={} command={}",
                    traceId, targetType == TargetType.DOCKER_CONTAINER ? "docker-exec" : "ssh-host", COMMAND_TIMEOUT.toSeconds(), command);

            var result = execute(traceId, host, sshPort, username, authType, secret, command);
            log.info("Java PID discovery command finished traceId={} exitStatus={} stdoutBytes={} stderrBytes={}",
                    traceId, result.exitStatus(), result.stdout().getBytes(StandardCharsets.UTF_8).length,
                    result.stderr().getBytes(StandardCharsets.UTF_8).length);

            if (result.exitStatus() != null && result.exitStatus() != 0) {
                log.warn("Java PID discovery command returned non-zero exit traceId={} exitStatus={} stderrPreview={}",
                        traceId, result.exitStatus(), preview(result.stderr()));
                return new DiscoveryController.JavaProcessDiscoveryResult(host, request.containerName(), List.of(), true);
            }
            var processes = parseProcesses(result.stdout(), username);
            log.info("Java PID discovery parsed processes traceId={} processCount={} pids={}",
                    traceId, processes.size(), processes.stream().map(DiscoveryController.JavaProcessInfo::processId).toList());
            return new DiscoveryController.JavaProcessDiscoveryResult(host, request.containerName(), processes, true);
        } catch (IOException error) {
            log.error("Java PID discovery failed traceId={} host={} sshPort={} username={} authType={} targetType={} containerName={} errorType={} message={}",
                    traceId, host, sshPort, username, authType, targetType, safeValue(request.containerName()),
                    error.getClass().getName(), error.getMessage(), error);
            throw new IllegalArgumentException("Java 进程发现失败：" + error.getMessage());
        } catch (RuntimeException error) {
            log.error("Java PID discovery failed before command completion traceId={} host={} sshPort={} username={} authType={} targetType={} containerName={} errorType={} message={}",
                    traceId, host, sshPort, username, authType, targetType, safeValue(request.containerName()),
                    error.getClass().getName(), error.getMessage(), error);
            throw error;
        }
    }

    private String resolveSecret(DiscoveryController.DiscoveryRequest request) {
        if (request.credential() != null && request.credential().secret() != null && !request.credential().secret().isBlank()) {
            return request.credential().secret();
        }
        var secret = credentialService.reveal(request.credentialId());
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("获取 PID 需要提供 SSH 凭据");
        }
        return secret;
    }

    private String buildDiscoveryCommand(TargetType targetType, String containerName) {
        if (targetType == TargetType.DOCKER_CONTAINER) {
            var container = required(containerName, "Docker 容器目标请先填写容器名称");
            return "docker inspect " + shellQuote(container) + " >/dev/null"
                    + " && docker exec " + shellQuote(container) + " sh -lc " + shellQuote(PROCESS_DISCOVERY_SCRIPT);
        }
        return "sh -lc " + shellQuote(PROCESS_DISCOVERY_SCRIPT);
    }

    private SshCommandResult execute(String traceId, String host, int port, String username, AuthType authType, String secret, String command)
            throws IOException {
        try (var ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            log.info("Java PID discovery SSH connecting traceId={} host={} sshPort={}", traceId, host, port);
            ssh.connect(host, port);
            log.info("Java PID discovery SSH connected traceId={} host={} sshPort={}", traceId, host, port);

            log.info("Java PID discovery SSH authenticating traceId={} username={} authType={}", traceId, username, authType);
            authenticate(ssh, username, authType, secret);
            log.info("Java PID discovery SSH authenticated traceId={} username={} authType={}", traceId, username, authType);

            try (var session = ssh.startSession()) {
                log.info("Java PID discovery SSH session opened traceId={}", traceId);
                var remoteCommand = session.exec(command);
                remoteCommand.join(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                var stdout = IOUtils.readFully(remoteCommand.getInputStream()).toString(StandardCharsets.UTF_8);
                var stderr = IOUtils.readFully(remoteCommand.getErrorStream()).toString(StandardCharsets.UTF_8);
                return new SshCommandResult(stdout, stderr, remoteCommand.getExitStatus());
            }
        }
    }

    private void authenticate(SSHClient ssh, String username, AuthType authType, String secret) throws IOException {
        if (authType == AuthType.SSH_KEY) {
            var keyFile = Files.createTempFile("fordring-ssh-key-", ".pem");
            try {
                Files.writeString(keyFile, secret, StandardCharsets.UTF_8);
                ssh.authPublickey(username, ssh.loadKeys(keyFile.toString()));
            } finally {
                Files.deleteIfExists(keyFile);
            }
            return;
        }
        ssh.authPassword(username, secret);
    }

    private List<DiscoveryController.JavaProcessInfo> parseProcesses(String output, String fallbackUser) {
        var processes = new ArrayList<DiscoveryController.JavaProcessInfo>();
        for (var line : output.split("\\R")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (looksLikePsOutput(trimmed)) {
                parsePsLine(trimmed).ifPresent(processes::add);
            } else {
                parseJpsLine(trimmed, fallbackUser).ifPresent(processes::add);
            }
        }
        return processes;
    }

    private java.util.Optional<DiscoveryController.JavaProcessInfo> parseJpsLine(String line, String fallbackUser) {
        var parts = line.split("\\s+", 3);
        if (parts.length < 2 || !isLong(parts[0])) {
            return java.util.Optional.empty();
        }
        var pid = Long.parseLong(parts[0]);
        var main = parts[1];
        if (main.endsWith(".Jps") || "Jps".equals(main)) {
            return java.util.Optional.empty();
        }
        var commandLine = parts.length > 2 ? "java " + main + " " + parts[2] : "java " + main;
        return java.util.Optional.of(new DiscoveryController.JavaProcessInfo(
                pid, processName(main), main, commandLine.trim(), fallbackUser));
    }

    private java.util.Optional<DiscoveryController.JavaProcessInfo> parsePsLine(String line) {
        var parts = line.split("\\s+", 3);
        if (parts.length < 3 || !isLong(parts[0])) {
            return java.util.Optional.empty();
        }
        var pid = Long.parseLong(parts[0]);
        var user = parts[1];
        var commandLine = parts[2];
        var main = extractMain(commandLine);
        return java.util.Optional.of(new DiscoveryController.JavaProcessInfo(
                pid, processName(main), main, commandLine, user));
    }

    private boolean looksLikePsOutput(String line) {
        var parts = line.split("\\s+", 3);
        return parts.length >= 3 && isLong(parts[0]);
    }

    private String extractMain(String commandLine) {
        var tokens = commandLine.split("\\s+");
        for (var i = 0; i < tokens.length; i++) {
            if ("-jar".equals(tokens[i]) && i + 1 < tokens.length) {
                return tokens[i + 1];
            }
        }
        var seenJava = false;
        for (var token : tokens) {
            if (!seenJava) {
                seenJava = token.endsWith("java") || token.endsWith("/java");
                continue;
            }
            if (!token.startsWith("-")) {
                return token;
            }
        }
        return "java";
    }

    private String processName(String main) {
        var slash = main.lastIndexOf('/');
        return slash >= 0 ? main.substring(slash + 1) : main;
    }

    private int normalizeSshPort(Integer port) {
        var value = port == null ? properties.access.defaultSshPort : port;
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("SSH 端口必须在 1-65535 之间");
        }
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String credentialSource(DiscoveryController.DiscoveryRequest request) {
        if (request.credential() != null && request.credential().secret() != null && !request.credential().secret().isBlank()) {
            return "inline";
        }
        if (request.credentialId() != null) {
            return "stored:" + request.credentialId();
        }
        return "missing";
    }

    private static String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        var normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private record SshCommandResult(String stdout, String stderr, Integer exitStatus) {
    }
}
