package com.fordring.arthas;

import com.fordring.audit.AuditService;
import com.fordring.common.enums.TargetType;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTarget;
import com.fordring.standalone.StandaloneArthasPackageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@Service
public class ArthasInstallationService {
    private static final Logger log = LoggerFactory.getLogger(ArthasInstallationService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(90);
    static final String HTTP_CLIENT_JAR_TARGET_PATH = "/tmp/fordring-arthas-http-client.jar";
    private static final String HTTP_CLIENT_JAR_RESOURCE = "/tools/fordring-arthas-http-client.jar";

    private static final String CHECK_SCRIPT = """
            BOOT="$HOME/.arthas/arthas-boot.jar"
            detect_version() {
              java -jar "$1" --version 2>&1 | sed -nE 's/.*([0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)?).*/\\1/p' | head -n 1
            }
            if [ -f "$BOOT" ]; then
              if command -v java >/dev/null 2>&1 && VERSION=$(detect_version "$BOOT"); then
                echo "installed=true"
                echo "source=$BOOT"
                echo "version=${VERSION:-unknown}"
                exit 0
              fi
              echo "installed=false"
              echo "source=$BOOT"
              echo "version=-"
              echo "reason=BOOT_INVALID"
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
            TMP="$HOME/.arthas/arthas-boot.jar.tmp.$$"
            VERSION_OUT="$HOME/.arthas/arthas-boot.version.$$"
            mkdir -p "$HOME/.arthas"
            cleanup() {
              rm -f "$TMP" "$VERSION_OUT"
            }
            trap cleanup EXIT
            validate_boot() {
              java -jar "$1" --version > "$VERSION_OUT" 2>&1
            }
            detect_version() {
              sed -nE 's/.*([0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)?).*/\\1/p' "$VERSION_OUT" | head -n 1
            }
            if ! command -v java >/dev/null 2>&1; then
              echo "JAVA_MISSING"
              exit 20
            fi
            if [ -f "$BOOT" ]; then
              if validate_boot "$BOOT"; then
                echo "ALREADY_INSTALLED"
                echo "source=$BOOT"
                echo "version=$(detect_version)"
                exit 0
              fi
              mv "$BOOT" "$BOOT.corrupt.$(date +%s)" 2>/dev/null || rm -f "$BOOT"
              echo "CORRUPT_BOOT_REPLACED"
            fi
            if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
              echo "DOWNLOADER_MISSING"
              exit 21
            fi
            ATTEMPT=1
            while [ "$ATTEMPT" -le 3 ]; do
              rm -f "$TMP"
              if command -v curl >/dev/null 2>&1; then
                curl -fL --retry 2 --retry-delay 1 --connect-timeout 10 --max-time 60 https://arthas.aliyun.com/arthas-boot.jar -o "$TMP" && validate_boot "$TMP" && mv "$TMP" "$BOOT" && break
              else
                wget -q -T 60 -O "$TMP" https://arthas.aliyun.com/arthas-boot.jar && validate_boot "$TMP" && mv "$TMP" "$BOOT" && break
              fi
              echo "INSTALL_RETRY_$ATTEMPT"
              ATTEMPT=$((ATTEMPT + 1))
              sleep 1
            done
            if [ ! -f "$BOOT" ] || ! validate_boot "$BOOT"; then
              echo "INSTALL_VALIDATE_FAILED"
              cat "$VERSION_OUT" 2>/dev/null || true
              exit 22
            fi
            echo "INSTALLED"
            echo "source=$BOOT"
            echo "version=$(detect_version)"
            """;

    private static final String ATTACH_SCRIPT_TEMPLATE = """
            set -e
            BOOT="$HOME/.arthas/arthas-boot.jar"
            PID=__PID__
            TELNET_PORT=__TELNET_PORT__
            HTTP_PORT=__HTTP_PORT__
            ARTHAS_USERNAME=__ARTHAS_USERNAME__
            ARTHAS_PASSWORD=__ARTHAS_PASSWORD__
            AUTHORIZATION_HEADER=__AUTHORIZATION_HEADER__
            ARTHAS_TARGET_IP=__ARTHAS_TARGET_IP__
            FORCE_RESTART=__FORCE_RESTART__
            LOG_FILE="/tmp/fordring-arthas-$PID.log"
            FORDRING_HTTP_CLIENT_JAR="/tmp/fordring-arthas-http-client.jar"
            api_exec() {
              CMD="$1"
              PAYLOAD=$(printf '{"action":"exec","command":"%s","execTimeout":"2000"}' "$CMD")
              if command -v curl >/dev/null 2>&1; then
                if [ -n "$ARTHAS_PASSWORD" ]; then
                  curl -sS --connect-timeout 1 --max-time 3 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -u "$ARTHAS_USERNAME:$ARTHAS_PASSWORD" -d "$PAYLOAD"
                else
                  curl -sS --connect-timeout 1 --max-time 3 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -d "$PAYLOAD"
                fi
                return $?
              fi
              if command -v wget >/dev/null 2>&1; then
                if [ -n "$ARTHAS_PASSWORD" ]; then
                  wget -q -O - --timeout=3 --user="$ARTHAS_USERNAME" --password="$ARTHAS_PASSWORD" --header='Content-Type: application/json' --post-data="$PAYLOAD" "http://127.0.0.1:$HTTP_PORT/api"
                else
                  wget -q -O - --timeout=3 --header='Content-Type: application/json' --post-data="$PAYLOAD" "http://127.0.0.1:$HTTP_PORT/api"
                fi
                return $?
              fi
              if [ -s "$FORDRING_HTTP_CLIENT_JAR" ] && command -v java >/dev/null 2>&1; then
                export FORDRING_ARTHAS_AUTHORIZATION="$AUTHORIZATION_HEADER"
                export FORDRING_ARTHAS_URL="http://127.0.0.1:$HTTP_PORT/api"
                export FORDRING_ARTHAS_PAYLOAD="$PAYLOAD"
                export FORDRING_ARTHAS_CONNECT_TIMEOUT_MS=1000
                export FORDRING_ARTHAS_READ_TIMEOUT_MS=3000
                java -jar "$FORDRING_HTTP_CLIENT_JAR"
                return $?
              fi
              echo "HTTP_CLIENT_MISSING"
              return 21
            }
            api_ok() {
              RESULT=$(api_exec version 2>&1) || return 1
              echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"'
            }
            port_listening() {
              if command -v ss >/dev/null 2>&1; then
                ss -ltn 2>/dev/null | grep -Eq "[:.]$HTTP_PORT[[:space:]]"
                return $?
              fi
              if command -v netstat >/dev/null 2>&1; then
                netstat -ltn 2>/dev/null | grep -Eq "[:.]$HTTP_PORT[[:space:]]"
                return $?
              fi
              return 1
            }
            if ! command -v java >/dev/null 2>&1; then
              echo "JAVA_MISSING"
              exit 20
            fi
            if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1 && [ ! -s "$FORDRING_HTTP_CLIENT_JAR" ]; then
              echo "HTTP_CLIENT_MISSING"
              exit 21
            fi
            if [ ! -f "$BOOT" ]; then
              echo "ARTHAS_BOOT_MISSING"
              exit 30
            fi
            if [ -z "$PID" ]; then
              echo "PID_MISSING"
              exit 31
            fi
            if ! kill -0 "$PID" 2>/dev/null; then
              echo "TARGET_PROCESS_MISSING"
              exit 34
            fi
            if api_ok; then
              if [ "$FORCE_RESTART" = "true" ]; then
                api_exec stop >/dev/null 2>&1 || true
                sleep 2
              else
                echo "ATTACHED_ALREADY"
                exit 0
              fi
            fi
            if port_listening; then
              echo "HTTP_PORT_OCCUPIED"
              echo "port=$HTTP_PORT"
              exit 35
            fi
            if [ -n "$ARTHAS_PASSWORD" ]; then
              nohup java -jar "$BOOT" --target-ip "$ARTHAS_TARGET_IP" --telnet-port "$TELNET_PORT" --http-port "$HTTP_PORT" --username "$ARTHAS_USERNAME" --password "$ARTHAS_PASSWORD" "$PID" > "$LOG_FILE" 2>&1 &
            else
              nohup java -jar "$BOOT" --target-ip "$ARTHAS_TARGET_IP" --telnet-port "$TELNET_PORT" --http-port "$HTTP_PORT" "$PID" > "$LOG_FILE" 2>&1 &
            fi
            for i in $(seq 1 45); do
              if api_ok; then
                echo "ATTACHED"
                exit 0
              fi
              sleep 1
            done
            echo "ATTACH_TIMEOUT"
            if ! kill -0 "$PID" 2>/dev/null; then
              echo "TARGET_PROCESS_EXITED"
            fi
            tail -n 80 "$LOG_FILE" 2>/dev/null || true
            exit 32
            """;

    private static final String COPY_HTTP_CLIENT_JAR_SCRIPT_TEMPLATE = """
            set -e
            SOURCE=__SOURCE__
            TARGET=__TARGET__
            if [ ! -s "$SOURCE" ]; then
              echo "FORDRING_HTTP_CLIENT_SOURCE_MISSING"
              exit 41
            fi
            mkdir -p "$(dirname "$TARGET")"
            cp "$SOURCE" "$TARGET"
            chmod 0644 "$TARGET"
            rm -f "$SOURCE"
            test -s "$TARGET"
            echo "FORDRING_HTTP_CLIENT_COPIED"
            """;

    private static final String COPY_HTTP_CLIENT_JAR_TO_CONTAINER_SCRIPT_TEMPLATE = """
            set -e
            CONTAINER=__CONTAINER__
            SOURCE=__SOURCE__
            TARGET=__TARGET__
            if [ ! -s "$SOURCE" ]; then
              echo "FORDRING_HTTP_CLIENT_SOURCE_MISSING"
              exit 41
            fi
            docker inspect "$CONTAINER" >/dev/null
            docker exec "$CONTAINER" sh -lc 'mkdir -p "$(dirname "__TARGET__")"'
            docker cp "$SOURCE" "$CONTAINER:$TARGET"
            docker exec "$CONTAINER" sh -lc 'chmod 0644 "__TARGET__" && test -s "__TARGET__"'
            rm -f "$SOURCE"
            echo "FORDRING_HTTP_CLIENT_COPIED"
            """;

    private static final String DETACH_SCRIPT_TEMPLATE = """
            set -e
            HTTP_PORT=__HTTP_PORT__
            PAYLOAD='{"action":"exec","command":"stop","execTimeout":"2000"}'
            AUTHORIZATION_HEADER=__AUTHORIZATION_HEADER__
            FORDRING_HTTP_CLIENT_JAR="/tmp/fordring-arthas-http-client.jar"
            if command -v curl >/dev/null 2>&1; then
              RESULT=$(curl -sS --connect-timeout 2 --max-time 5 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -H "Authorization: $AUTHORIZATION_HEADER" -d "$PAYLOAD" 2>&1) && echo "$RESULT" && echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "DETACHED" && exit 0
              echo "$RESULT" | grep -Eiq 'Connection refused|Failed to connect|Could not connect|Connection reset|Empty reply from server' && echo "ALREADY_DETACHED" && exit 0
              echo "$RESULT"
              exit 33
            elif command -v wget >/dev/null 2>&1; then
              RESULT=$(wget -q -O - --timeout=5 --header='Content-Type: application/json' --header="Authorization: $AUTHORIZATION_HEADER" --post-data="$PAYLOAD" "http://127.0.0.1:$HTTP_PORT/api" 2>&1) && echo "$RESULT" && echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "DETACHED" && exit 0
              echo "$RESULT" | grep -Eiq 'Connection refused|Connection reset|refused|Empty reply from server' && echo "ALREADY_DETACHED" && exit 0
              echo "$RESULT"
              exit 33
            fi
            if [ -s "$FORDRING_HTTP_CLIENT_JAR" ] && command -v java >/dev/null 2>&1; then
              export FORDRING_ARTHAS_URL="http://127.0.0.1:$HTTP_PORT/api"
              export FORDRING_ARTHAS_PAYLOAD="$PAYLOAD"
              export FORDRING_ARTHAS_AUTHORIZATION="$AUTHORIZATION_HEADER"
              export FORDRING_ARTHAS_CONNECT_TIMEOUT_MS=2000
              export FORDRING_ARTHAS_READ_TIMEOUT_MS=5000
              RESULT=$(java -jar "$FORDRING_HTTP_CLIENT_JAR" 2>&1) && echo "$RESULT" && echo "$RESULT" | grep -q '"state"[[:space:]]*:[[:space:]]*"SUCCEEDED"' && echo "DETACHED" && exit 0
              echo "$RESULT" | grep -Eiq 'Connection refused|Failed to connect|Could not connect|Connection reset|Empty reply from server' && echo "ALREADY_DETACHED" && exit 0
              echo "$RESULT"
              exit 33
            fi
            echo "DOWNLOADER_MISSING"
            exit 21
            """;

    private static final String OFFLINE_INSTALL_SCRIPT_TEMPLATE = """
            set -e
            SOURCE=__SOURCE__
            EXPECTED_SHA256=__EXPECTED_SHA256__
            BOOT="$HOME/.arthas/arthas-boot.jar"
            VERSION_OUT="$HOME/.arthas/arthas-boot.version.$$"
            STAGE="$HOME/.arthas/fordring-install.$$"
            HTTP_CLIENT="/tmp/fordring-arthas-http-client.jar"
            mkdir -p "$HOME/.arthas"
            cleanup() {
              rm -f "$SOURCE" "$VERSION_OUT"
              rm -rf "$STAGE"
            }
            trap cleanup EXIT
            validate_boot() {
              java -jar "$1" --version > "$VERSION_OUT" 2>&1
            }
            detect_version() {
              sed -nE 's/.*([0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)?).*/\\1/p' "$VERSION_OUT" | head -n 1
            }
            if ! command -v java >/dev/null 2>&1; then
              echo "JAVA_MISSING"
              exit 20
            fi
            if [ -f "$BOOT" ] && validate_boot "$BOOT"; then
              echo "ALREADY_INSTALLED"
              echo "source=$BOOT"
              echo "version=$(detect_version)"
              exit 0
            fi
            if [ ! -s "$HTTP_CLIENT" ]; then
              echo "FORDRING_HTTP_CLIENT_MISSING"
              exit 23
            fi
            ACTUAL_SHA256=$(java -jar "$HTTP_CLIENT" --sha256 "$SOURCE")
            if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
              echo "SHA256_MISMATCH"
              exit 24
            fi
            rm -rf "$STAGE"
            mkdir -p "$STAGE"
            if ! java -jar "$HTTP_CLIENT" --extract-zip "$SOURCE" "$STAGE"; then
              echo "PACKAGE_EXTRACT_FAILED"
              exit 25
            fi
            if ! validate_boot "$STAGE/arthas-boot.jar"; then
              echo "INSTALL_VALIDATE_FAILED"
              exit 22
            fi
            rm -f "$BOOT"
            rm -rf "$HOME/.arthas/lib" "$HOME/.arthas/async-profiler"
            cp -R "$STAGE"/. "$HOME/.arthas"/
            if ! validate_boot "$BOOT"; then
              echo "INSTALL_VALIDATE_FAILED"
              exit 22
            fi
            echo "INSTALLED"
            echo "source=$BOOT"
            echo "version=$(detect_version)"
            """;

    private final TargetShellExecutor shellExecutor;
    private final AuditService auditService;
    private final FordringProperties properties;
    private final StandaloneArthasPackageProvider standalonePackageProvider;

    public ArthasInstallationService(TargetShellExecutor shellExecutor, AuditService auditService, FordringProperties properties,
                                     ObjectProvider<StandaloneArthasPackageProvider> standalonePackageProvider) {
        this.shellExecutor = shellExecutor;
        this.auditService = auditService;
        this.properties = properties;
        this.standalonePackageProvider = standalonePackageProvider.getIfAvailable();
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
            return new Result(installed, message, cleanVersion(extractValue(result.stdout(), "version")),
                    extractValue(result.stdout(), "source"), traceId, preview(result.stdout()), false);
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
            if (properties.standalone.enabled) {
                return installOffline(target, operatorName, traceId);
            }
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
            copyHttpClientJar(target, traceId, operatorName);
            auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(true, "Arthas 安装完成", extractInstalledVersion(result.stdout()),
                    extractValue(result.stdout(), "source"), traceId, preview(result.stdout()), false);
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
        return attach(target, false, operatorName);
    }

    public Result attach(AccessTarget target, boolean forceRestart, String operatorName) {
        var traceId = traceId("arthas-attach");
        log.info("Arthas attach started traceId={} targetId={} host={} sshPort={} processId={} telnetPort={} httpPort={} targetType={} containerName={} forceRestart={} operator={}",
                traceId, target.id, target.host, target.sshPort, target.processId, target.telnetPort, target.httpPort,
                target.targetType, safeValue(target.containerName), forceRestart, operatorName);
        try {
            if (properties.standalone.enabled) {
                copyHttpClientJar(target, traceId, operatorName);
            }
            var command = shellExecutor.buildTargetCommand(target, attachScript(target, forceRestart));
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
            var attachedByCurrentRequest = result.stdout().lines().anyMatch("ATTACHED"::equals);
            return new Result(true, "Arthas 已接入", "-", "-", traceId, preview(result.stdout()), attachedByCurrentRequest);
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
            if (properties.standalone.enabled) {
                copyHttpClientJar(target, traceId, operatorName);
            }
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
            return new Result(true, "Arthas 已断开", "-", "-", traceId, preview(result.stdout()), false);
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
        if (result.stdout().contains("INSTALL_VALIDATE_FAILED")) {
            return "Arthas 下载完成后校验失败，请检查目标机网络或手动清理 ~/.arthas/arthas-boot.jar 后重试："
                    + preview(result.stdout());
        }
        if (result.stdout().contains("FORDRING_HTTP_CLIENT_MISSING")) {
            return "目标环境未找到 Fordring Arthas HTTP Client，无法校验和解压离线 Arthas 包";
        }
        if (result.stdout().contains("SHA256_MISMATCH")) {
            return "上传到目标环境的离线 Arthas 包 SHA-256 校验失败";
        }
        if (result.stdout().contains("PACKAGE_EXTRACT_FAILED")) {
            return "上传到目标环境的离线 Arthas 包解压失败";
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
        if (result.stdout().contains("TARGET_PROCESS_MISSING")) {
            return "目标 Java 进程不存在或当前用户无权限访问，请重新获取 PID 后再接入";
        }
        if (result.stdout().contains("HTTP_CLIENT_MISSING")) {
            return "目标环境未找到 curl 或 wget，无法探活 Arthas HTTP API";
        }
        if (result.stdout().contains("HTTP_PORT_OCCUPIED")) {
            return "Arthas HTTP 端口已被占用且不是可用的 Arthas API，请更换 HTTP 端口或清理目标环境中的旧进程："
                    + preview(result.stdout());
        }
        if (result.stdout().contains("ATTACH_TIMEOUT")) {
            return "Arthas 启动后 HTTP API 探活超时，Java 进程 PID 可能已变化，请在接入管理中重新获取 PID 后再接入："
                    + preview(result.stdout());
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private String detachFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("DOWNLOADER_MISSING")) {
            return "目标环境未找到 curl 或 wget，无法调用 Arthas shutdown";
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private void copyHttpClientJar(AccessTarget target, String traceId, String operatorName) {
        Path localJar = null;
        var remoteHostPath = "/tmp/fordring-arthas-http-client-" + UUID.randomUUID().toString().substring(0, 8) + ".jar";
        try {
            localJar = extractHttpClientJar();
            shellExecutor.upload(traceId, target, localJar, remoteHostPath);
            var copyCommand = target.targetType == TargetType.DOCKER_CONTAINER
                    ? copyHttpClientJarToContainerScript(target, remoteHostPath)
                    : copyHttpClientJarScript(remoteHostPath);
            var result = shellExecutor.execute(traceId, target, copyCommand, COMMAND_TIMEOUT);
            var success = result.exitStatus() != null && result.exitStatus() == 0
                    && result.stdout().contains("FORDRING_HTTP_CLIENT_COPIED");
            log.info("Fordring Arthas HTTP client jar copy finished traceId={} targetId={} success={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, success, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            if (!success) {
                var message = copyHttpClientJarFailureMessage(result);
                auditService.record("ARTHAS_HTTP_CLIENT_COPY", "ACCESS_TARGET", target.id, operatorName, "FAILED", message, null, null);
                throw new IllegalArgumentException("复制 Fordring Arthas HTTP Client 失败：" + message);
            }
            auditService.record("ARTHAS_HTTP_CLIENT_COPY", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
        } catch (IOException error) {
            log.error("Fordring Arthas HTTP client jar copy failed traceId={} targetId={} host={} sshPort={} errorType={} message={}",
                    traceId, target.id, target.host, target.sshPort, error.getClass().getName(), error.getMessage(), error);
            auditService.record("ARTHAS_HTTP_CLIENT_COPY", "ACCESS_TARGET", target.id, operatorName, "FAILED", error.getMessage(), null, null);
            throw new IllegalArgumentException("复制 Fordring Arthas HTTP Client 失败：" + error.getMessage());
        } finally {
            if (localJar != null) {
                try {
                    Files.deleteIfExists(localJar);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Result installOffline(AccessTarget target, String operatorName, String traceId) throws IOException {
        if (standalonePackageProvider == null) {
            throw new IOException("standalone Arthas package provider 未初始化");
        }
        var extracted = standalonePackageProvider.extract();
        var remoteHostPath = "/tmp/fordring-arthas-bin-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
        var targetPath = "/tmp/fordring-arthas-bin-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
        try {
            copyHttpClientJar(target, traceId, operatorName);
            shellExecutor.upload(traceId, target, extracted.path(), remoteHostPath);
            var offlineScript = offlineInstallScript(target.targetType == TargetType.DOCKER_CONTAINER ? targetPath : remoteHostPath,
                    extracted.sha256());
            var command = target.targetType == TargetType.DOCKER_CONTAINER
                    ? "docker cp " + shellQuote(remoteHostPath) + " " + shellQuote(target.containerName + ":" + targetPath)
                    + " && rm -f " + shellQuote(remoteHostPath)
                    + " && " + shellExecutor.buildTargetCommand(target, offlineScript)
                    : shellExecutor.buildTargetCommand(target, offlineScript);
            var result = shellExecutor.execute(traceId, target, command, COMMAND_TIMEOUT);
            var success = result.exitStatus() != null && result.exitStatus() == 0;
            log.info("Offline Arthas installation finished traceId={} targetId={} success={} exitStatus={} stdoutPreview={} stderrPreview={}",
                    traceId, target.id, success, result.exitStatus(), preview(result.stdout()), preview(result.stderr()));
            if (!success) {
                var message = installFailureMessage(result);
                auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "FAILED", message, null, null);
                throw new IllegalArgumentException("安装 Arthas 失败：" + message);
            }
            auditService.record("ARTHAS_INSTALL", "ACCESS_TARGET", target.id, operatorName, "SUCCESS", null, null, null);
            return new Result(true, "Arthas 安装完成", extractInstalledVersion(result.stdout()),
                    extractValue(result.stdout(), "source"), traceId, preview(result.stdout()), false);
        } finally {
            Files.deleteIfExists(extracted.path());
        }
    }

    private Path extractHttpClientJar() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(HTTP_CLIENT_JAR_RESOURCE)) {
            if (input == null) {
                throw new IOException("后端包缺少资源 " + HTTP_CLIENT_JAR_RESOURCE);
            }
            var localJar = Files.createTempFile("fordring-arthas-http-client-", ".jar");
            Files.copy(input, localJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return localJar;
        }
    }

    private String copyHttpClientJarScript(String remoteHostPath) {
        return COPY_HTTP_CLIENT_JAR_SCRIPT_TEMPLATE
                .replace("__SOURCE__", shellQuote(remoteHostPath))
                .replace("__TARGET__", shellQuote(HTTP_CLIENT_JAR_TARGET_PATH));
    }

    private String copyHttpClientJarToContainerScript(AccessTarget target, String remoteHostPath) {
        return COPY_HTTP_CLIENT_JAR_TO_CONTAINER_SCRIPT_TEMPLATE
                .replace("__CONTAINER__", shellQuote(target.containerName))
                .replace("__SOURCE__", shellQuote(remoteHostPath))
                .replace("__TARGET__", HTTP_CLIENT_JAR_TARGET_PATH);
    }

    private String copyHttpClientJarFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("FORDRING_HTTP_CLIENT_SOURCE_MISSING")) {
            return "上传到目标主机的 Fordring Arthas HTTP Client 文件不存在或为空";
        }
        if (result.stdout().contains("No such object")) {
            return "Docker 容器不存在：" + preview(result.stdout());
        }
        return preview(result.stderr().isBlank() ? result.stdout() : result.stderr());
    }

    private String attachScript(AccessTarget target, boolean forceRestart) {
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
                .replace("__ARTHAS_PASSWORD__", shellQuote(properties.arthas.password == null ? "" : properties.arthas.password))
                .replace("__AUTHORIZATION_HEADER__", shellQuote(authorizationHeader()))
                .replace("__ARTHAS_TARGET_IP__", properties.standalone.enabled ? "127.0.0.1" : "0.0.0.0")
                .replace("__FORCE_RESTART__", forceRestart ? "true" : "false");
    }

    private String detachScript(AccessTarget target) {
        if (target.httpPort == null) {
            throw new IllegalArgumentException("目标缺少 Arthas HTTP 端口");
        }
        return DETACH_SCRIPT_TEMPLATE
                .replace("__HTTP_PORT__", target.httpPort.toString())
                .replace("__AUTHORIZATION_HEADER__", shellQuote(authorizationHeader()));
    }

    private String offlineInstallScript(String source, String sha256) {
        return OFFLINE_INSTALL_SCRIPT_TEMPLATE
                .replace("__SOURCE__", shellQuote(source))
                .replace("__EXPECTED_SHA256__", shellQuote(sha256));
    }

    private String authorizationHeader() {
        if (properties.arthas.password == null || properties.arthas.password.isBlank()) {
            return "";
        }
        var username = properties.arthas.username == null || properties.arthas.username.isBlank()
                ? "arthas"
                : properties.arthas.username;
        var credential = username + ":" + properties.arthas.password;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String extractInstalledVersion(String output) {
        var version = cleanVersion(extractValue(output, "version"));
        if (!"-".equals(version)) {
            return version;
        }
        for (var line : output.split("\\R")) {
            var trimmed = line.trim();
            if (looksLikeVersion(trimmed)) {
                return trimmed;
            }
        }
        return "-";
    }

    private static String cleanVersion(String value) {
        if (value == null || value.isBlank() || "unknown".equals(value) || "-".equals(value)) {
            return value == null || value.isBlank() ? "-" : value;
        }
        for (var token : value.split("[^0-9A-Za-z.-]+")) {
            if (looksLikeVersion(token)) {
                return token;
            }
        }
        return "unknown";
    }

    private static boolean looksLikeVersion(String value) {
        return value != null && value.matches("[0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)?");
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

    public record Result(boolean installed, String message, String version, String installationPath, String traceId,
                         String outputPreview, boolean attachedByCurrentRequest) {
    }

}
