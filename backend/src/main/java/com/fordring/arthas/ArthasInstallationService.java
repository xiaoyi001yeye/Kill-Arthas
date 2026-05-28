package com.fordring.arthas;

import com.fordring.audit.AuditService;
import com.fordring.common.enums.TargetType;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Service
public class ArthasInstallationService {
    private static final Logger log = LoggerFactory.getLogger(ArthasInstallationService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);

    private static final String CHECK_SCRIPT = """
            BOOT="$HOME/.arthas/arthas-boot.jar"
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
            echo "version=-"
            """;

    private static final String INSTALL_SCRIPT = """
            set -e
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
            java -jar "$BOOT" --version 2>/dev/null | head -n 1 || true
            """;

    private static final String ATTACH_SCRIPT_TEMPLATE = """
            set -e
            BOOT="$HOME/.arthas/arthas-boot.jar"
            PID=__PID__
            TELNET_PORT=__TELNET_PORT__
            HTTP_PORT=__HTTP_PORT__
            ARTHAS_USERNAME=__ARTHAS_USERNAME__
            ARTHAS_PASSWORD=__ARTHAS_PASSWORD__
            LOG_FILE="/tmp/fordring-arthas-$PID.log"
            if ! command -v java >/dev/null 2>&1; then
              echo "JAVA_MISSING"
              exit 20
            fi
            if [ ! -f "$BOOT" ]; then
              echo "ARTHAS_BOOT_MISSING"
              exit 30
            fi
            if [ -z "$PID" ]; then
              echo "PID_MISSING"
              exit 31
            fi
            if [ -n "$ARTHAS_PASSWORD" ]; then
              nohup java -jar "$BOOT" --target-ip 0.0.0.0 --telnet-port "$TELNET_PORT" --http-port "$HTTP_PORT" --username "$ARTHAS_USERNAME" --password "$ARTHAS_PASSWORD" "$PID" > "$LOG_FILE" 2>&1 &
            else
              nohup java -jar "$BOOT" --target-ip 0.0.0.0 --telnet-port "$TELNET_PORT" --http-port "$HTTP_PORT" "$PID" > "$LOG_FILE" 2>&1 &
            fi
            for i in $(seq 1 20); do
              if command -v curl >/dev/null 2>&1; then
                RESULT=$(curl -sS --connect-timeout 1 --max-time 2 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -d '{"action":"exec","command":"version","execTimeout":"2000"}' 2>/dev/null || true)
                echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "ATTACHED" && exit 0
              elif command -v wget >/dev/null 2>&1; then
                RESULT=$(wget -q -O - --timeout=2 --header='Content-Type: application/json' --post-data='{"action":"exec","command":"version","execTimeout":"2000"}' "http://127.0.0.1:$HTTP_PORT/api" 2>/dev/null || true)
                echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "ATTACHED" && exit 0
              fi
              sleep 1
            done
            echo "ATTACH_TIMEOUT"
            tail -n 80 "$LOG_FILE" 2>/dev/null || true
            exit 32
            """;

    private static final String DETACH_SCRIPT_TEMPLATE = """
            set -e
            HTTP_PORT=__HTTP_PORT__
            PAYLOAD='{"action":"exec","command":"stop","execTimeout":"2000"}'
            if command -v curl >/dev/null 2>&1; then
              RESULT=$(curl -sS --connect-timeout 2 --max-time 5 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -d "$PAYLOAD" 2>&1) && echo "$RESULT" && echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "DETACHED" && exit 0
              echo "$RESULT" | grep -Eiq 'Connection refused|Failed to connect|Could not connect|Connection reset|Empty reply from server' && echo "ALREADY_DETACHED" && exit 0
              echo "$RESULT"
              exit 33
            elif command -v wget >/dev/null 2>&1; then
              RESULT=$(wget -q -O - --timeout=5 --header='Content-Type: application/json' --post-data="$PAYLOAD" "http://127.0.0.1:$HTTP_PORT/api" 2>&1) && echo "$RESULT" && echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "DETACHED" && exit 0
              echo "$RESULT" | grep -Eiq 'Connection refused|Connection reset|refused|Empty reply from server' && echo "ALREADY_DETACHED" && exit 0
              echo "$RESULT"
              exit 33
            fi
            echo "DOWNLOADER_MISSING"
            exit 21
            """;

    private final TargetShellExecutor shellExecutor;
    private final AuditService auditService;
    private final FordringProperties properties;

    public ArthasInstallationService(TargetShellExecutor shellExecutor, AuditService auditService, FordringProperties properties) {
        this.shellExecutor = shellExecutor;
        this.auditService = auditService;
        this.properties = properties;
    }

    public Result check(AccessTarget target, String operatorName) {
        var traceId = traceId("arthas-check");
        log.info("Arthas installation check started traceId={} targetId={} host={} sshPort={} username={} authType={} operator={}",
                traceId, target.id, target.host, target.sshPort, target.username, target.authType, operatorName);
        try {
            var command = shellExecutor.buildTargetCommand(target, CHECK_SCRIPT);
            log.info("Arthas installation check command prepared traceId={} strategy={} containerName={}",
                    traceId, strategy(target), safeValue(target.containerName));
            var result = shellExecutor.execute(traceId, target, command, COMMAND_TIMEOUT);
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
            var command = shellExecutor.buildTargetCommand(target, INSTALL_SCRIPT);
            log.info("Arthas installation command prepared traceId={} strategy={} containerName={}",
                    traceId, strategy(target), safeValue(target.containerName));
            var result = shellExecutor.execute(traceId, target, command, COMMAND_TIMEOUT);
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

    public Result attach(AccessTarget target, String operatorName) {
        var traceId = traceId("arthas-attach");
        log.info("Arthas attach started traceId={} targetId={} host={} sshPort={} processId={} telnetPort={} httpPort={} targetType={} containerName={} operator={}",
                traceId, target.id, target.host, target.sshPort, target.processId, target.telnetPort, target.httpPort,
                target.targetType, safeValue(target.containerName), operatorName);
        try {
            var command = shellExecutor.buildTargetCommand(target, attachScript(target));
            log.info("Arthas attach command prepared traceId={} strategy={} containerName={}",
                    traceId, strategy(target), safeValue(target.containerName));
            var result = shellExecutor.execute(traceId, target, command, COMMAND_TIMEOUT);
            var success = result.exitStatus() != null && result.exitStatus() == 0 && result.stdout().contains("ATTACHED");
            log.info("Arthas attach command finished traceId={} targetId={} success={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, success, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            if (!success) {
                var message = attachFailureMessage(result);
                auditService.record("ARTHAS_ATTACH", "ACCESS_TARGET", target.id, operatorName, "FAILED", message, null, null);
                throw new IllegalArgumentException("接入 Arthas 失败：" + message);
            }
            auditService.record("ARTHAS_ATTACH", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(true, "Arthas 已接入", "-", traceId, preview(result.stdout()));
        } catch (IOException error) {
            log.error("Arthas attach failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_ATTACH", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("接入 Arthas 失败：" + error.getMessage());
        } catch (RuntimeException error) {
            log.error("Arthas attach failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            throw error;
        }
    }

    public Result detach(AccessTarget target, String operatorName) {
        var traceId = traceId("arthas-detach");
        log.info("Arthas detach started traceId={} targetId={} host={} sshPort={} httpPort={} targetType={} containerName={} operator={}",
                traceId, target.id, target.host, target.sshPort, target.httpPort,
                target.targetType, safeValue(target.containerName), operatorName);
        try {
            var command = shellExecutor.buildTargetCommand(target, detachScript(target));
            log.info("Arthas detach command prepared traceId={} strategy={} containerName={}",
                    traceId, strategy(target), safeValue(target.containerName));
            var result = shellExecutor.execute(traceId, target, command, COMMAND_TIMEOUT);
            var success = result.exitStatus() != null && result.exitStatus() == 0
                    && (result.stdout().contains("DETACHED") || result.stdout().contains("ALREADY_DETACHED"));
            log.info("Arthas detach command finished traceId={} targetId={} success={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, success, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            if (!success) {
                var message = detachFailureMessage(result);
                auditService.record("ARTHAS_DETACH", "ACCESS_TARGET", target.id, operatorName, "FAILED", message, null, null);
                throw new IllegalArgumentException("断开 Arthas 失败：" + message);
            }
            auditService.record("ARTHAS_DETACH", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(true, "Arthas 已断开", "-", traceId, preview(result.stdout()));
        } catch (IOException error) {
            log.error("Arthas detach failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_DETACH", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("断开 Arthas 失败：" + error.getMessage());
        } catch (RuntimeException error) {
            log.error("Arthas detach failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            throw error;
        }
    }

    private String installFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("JAVA_MISSING")) {
            return "目标主机未找到 java 命令";
        }
        if (result.stdout().contains("DOWNLOADER_MISSING")) {
            return "目标主机未找到 curl 或 wget";
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private String attachFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("JAVA_MISSING")) {
            return "目标环境未找到 java 命令";
        }
        if (result.stdout().contains("ARTHAS_BOOT_MISSING")) {
            return "目标环境未找到 ~/.arthas/arthas-boot.jar，请先安装 Arthas";
        }
        if (result.stdout().contains("PID_MISSING")) {
            return "目标缺少 Java 进程 PID";
        }
        if (result.stdout().contains("ATTACH_TIMEOUT")) {
            return "Arthas 启动后 HTTP API 探活超时：" + preview(result.stdout());
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private String detachFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("DOWNLOADER_MISSING")) {
            return "目标环境未找到 curl 或 wget，无法调用 Arthas shutdown";
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private String attachScript(AccessTarget target) {
        if (target.processId == null) {
            throw new IllegalArgumentException("目标缺少 Java 进程 PID");
        }
        if (target.telnetPort == null || target.httpPort == null) {
            throw new IllegalArgumentException("目标缺少 Arthas 通道端口");
        }
        return ATTACH_SCRIPT_TEMPLATE
                .replace("__PID__", target.processId.toString())
                .replace("__TELNET_PORT__", target.telnetPort.toString())
                .replace("__HTTP_PORT__", target.httpPort.toString())
                .replace("__ARTHAS_USERNAME__", shellQuote(properties.arthas.username == null ? "arthas" : properties.arthas.username))
                .replace("__ARTHAS_PASSWORD__", shellQuote(properties.arthas.password == null ? "" : properties.arthas.password));
    }

    private String detachScript(AccessTarget target) {
        if (target.httpPort == null) {
            throw new IllegalArgumentException("目标缺少 Arthas HTTP 端口");
        }
        return DETACH_SCRIPT_TEMPLATE
                .replace("__HTTP_PORT__", target.httpPort.toString());
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

    private static String shellQuote(String value) {
        return TargetShellExecutor.shellQuote(value);
    }

    private static String safeValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String strategy(AccessTarget target) {
        return target.targetType == TargetType.DOCKER_CONTAINER ? "docker-exec" : "ssh-host";
    }

    public record Result(boolean installed, String message, String version, String traceId, String outputPreview) {
    }

}
