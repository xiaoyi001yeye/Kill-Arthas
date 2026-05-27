package com.fordring.arthas;

import com.fordring.audit.AuditService;
import com.fordring.common.enums.AuthType;
import com.fordring.credential.CredentialService;
import com.fordring.target.AccessTarget;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ArthasInstallationService {
    private static final Logger log = LoggerFactory.getLogger(ArthasInstallationService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);

    private static final String CHECK_COMMAND = """
            sh -lc 'BOOT="$HOME/.arthas/arthas-boot.jar"
            if [ -f "$BOOT" ]; then
              VERSION=$(java -jar "$BOOT" --version 2>/dev/null | head -n 1 || true)
              echo "installed=true"
              echo "source=$BOOT"
              echo "version=${VERSION:-unknown}"
              exit 0
            fi
            if command -v as.sh >/dev/null 2>&1; then
              echo "installed=true"
              echo "source=$(command -v as.sh)"
              echo "version=unknown"
              exit 0
            fi
            if [ -d "$HOME/.arthas/lib" ]; then
              echo "installed=true"
              echo "source=$HOME/.arthas/lib"
              echo "version=unknown"
              exit 0
            fi
            echo "installed=false"
            echo "source=-"
            echo "version=-"'
            """;

    private static final String INSTALL_COMMAND = """
            sh -lc 'set -e
            BOOT="$HOME/.arthas/arthas-boot.jar"
            mkdir -p "$HOME/.arthas"
            if ! command -v java >/dev/null 2>&1; then
              echo "JAVA_MISSING"
              exit 20
            fi
            if [ -f "$BOOT" ]; then
              echo "ALREADY_INSTALLED"
              java -jar "$BOOT" --version 2>/dev/null | head -n 1 || true
              exit 0
            fi
            if command -v curl >/dev/null 2>&1; then
              curl -fsSL --connect-timeout 10 --max-time 60 https://arthas.aliyun.com/arthas-boot.jar -o "$BOOT"
            elif command -v wget >/dev/null 2>&1; then
              wget -q -T 60 -O "$BOOT" https://arthas.aliyun.com/arthas-boot.jar
            else
              echo "DOWNLOADER_MISSING"
              exit 21
            fi
            echo "INSTALLED"
            echo "source=$BOOT"
            java -jar "$BOOT" --version 2>/dev/null | head -n 1 || true'
            """;

    private final CredentialService credentialService;
    private final AuditService auditService;

    public ArthasInstallationService(CredentialService credentialService, AuditService auditService) {
        this.credentialService = credentialService;
        this.auditService = auditService;
    }

    public Result check(AccessTarget target, String operatorName) {
        var traceId = traceId("arthas-check");
        log.info("Arthas installation check started traceId={} targetId={} host={} sshPort={} username={} authType={} operator={}",
                traceId, target.id, target.host, target.sshPort, target.username, target.authType, operatorName);
        try {
            var result = execute(traceId, target, CHECK_COMMAND);
            var installed = result.stdout().contains("installed=true");
            var message = installed ? "目标主机已安装 Arthas" : "目标主机未发现 Arthas";
            log.info("Arthas installation check finished traceId={} targetId={} installed={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, installed, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            auditService.record("ARTHAS_INSTALLATION_CHECK", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(installed, message, extractValue(result.stdout(), "version"), traceId, preview(result.stdout()));
        } catch (IOException error) {
            log.error("Arthas installation check failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_INSTALLATION_CHECK", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("检查 Arthas 失败：" + error.getMessage());
        } catch (RuntimeException error) {
            log.error("Arthas installation check failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_INSTALLATION_CHECK", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw error;
        }
    }

    public Result install(AccessTarget target, String operatorName) {
        var traceId = traceId("arthas-install");
        log.info("Arthas installation started traceId={} targetId={} host={} sshPort={} username={} authType={} operator={} commandTimeoutSeconds={}",
                traceId, target.id, target.host, target.sshPort, target.username, target.authType, operatorName, COMMAND_TIMEOUT.toSeconds());
        try {
            var result = execute(traceId, target, INSTALL_COMMAND);
            var success = result.exitStatus() != null && result.exitStatus() == 0;
            log.info("Arthas installation command finished traceId={} targetId={} success={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, success, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            if (!success) {
                var message = installFailureMessage(result);
                auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "FAILED", message, null, null);
                throw new IllegalArgumentException("安装 Arthas 失败：" + message);
            }
            auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(true, "Arthas 安装完成", extractInstalledVersion(result.stdout()), traceId, preview(result.stdout()));
        } catch (IOException error) {
            log.error("Arthas installation failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("安装 Arthas 失败：" + error.getMessage());
        } catch (RuntimeException error) {
            log.error("Arthas installation failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            throw error;
        }
    }

    private SshCommandResult execute(String traceId, AccessTarget target, String command) throws IOException {
        var secret = credentialService.reveal(target.credentialId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("目标缺少 SSH 凭据");
        }
        try (var ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            log.info("Arthas installation SSH connecting traceId={} host={} sshPort={}", traceId, target.host, target.sshPort);
            ssh.connect(target.host, target.sshPort);
            log.info("Arthas installation SSH connected traceId={} host={} sshPort={}", traceId, target.host, target.sshPort);
            authenticate(ssh, target.username, target.authType, secret);
            log.info("Arthas installation SSH authenticated traceId={} username={} authType={}", traceId, target.username, target.authType);
            try (var session = ssh.startSession()) {
                log.info("Arthas installation SSH session opened traceId={}", traceId);
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

    private String installFailureMessage(SshCommandResult result) {
        if (result.stdout().contains("JAVA_MISSING")) {
            return "目标主机未找到 java 命令";
        }
        if (result.stdout().contains("DOWNLOADER_MISSING")) {
            return "目标主机未找到 curl 或 wget";
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private static String extractInstalledVersion(String output) {
        var version = extractValue(output, "version");
        if (!"-".equals(version)) {
            return version;
        }
        for (var line : output.split("\\R")) {
            var trimmed = line.trim();
            if (!trimmed.isBlank() && !trimmed.contains("=") && !trimmed.equals("INSTALLED") && !trimmed.equals("ALREADY_INSTALLED")) {
                return trimmed;
            }
        }
        return "-";
    }

    private static String extractValue(String output, String key) {
        var prefix = key + "=";
        for (var line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "-";
    }

    private static String traceId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        var normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    public record Result(boolean installed, String message, String version, String traceId, String outputPreview) {
    }

    private record SshCommandResult(String stdout, String stderr, Integer exitStatus) {
    }
}
