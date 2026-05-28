package com.fordring.arthas;

import com.fordring.common.enums.AuthType;
import com.fordring.common.enums.TargetType;
import com.fordring.credential.CredentialService;
import com.fordring.target.AccessTarget;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class TargetShellExecutor {
    private static final Logger log = LoggerFactory.getLogger(TargetShellExecutor.class);

    private final CredentialService credentialService;

    public TargetShellExecutor(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    public ShellResult execute(String traceId, AccessTarget target, String command, Duration timeout) throws IOException {
        var secret = credentialService.reveal(target.credentialId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("目标缺少 SSH 凭据");
        }
        try (var ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            log.info("Target shell SSH connecting traceId={} host={} sshPort={} strategy={}",
                    traceId, target.host, target.sshPort, strategy(target));
            ssh.connect(target.host, target.sshPort);
            authenticate(ssh, target.username, target.authType, secret);
            log.info("Target shell SSH authenticated traceId={} username={} authType={}", traceId, target.username, target.authType);
            try (var session = ssh.startSession()) {
                var remoteCommand = session.exec(command);
                remoteCommand.join(timeout.toSeconds(), TimeUnit.SECONDS);
                var stdout = IOUtils.readFully(remoteCommand.getInputStream()).toString(StandardCharsets.UTF_8);
                var stderr = IOUtils.readFully(remoteCommand.getErrorStream()).toString(StandardCharsets.UTF_8);
                return new ShellResult(stdout, stderr, remoteCommand.getExitStatus());
            }
        }
    }

    public String buildTargetCommand(AccessTarget target, String script) {
        if (target.targetType == TargetType.DOCKER_CONTAINER) {
            var container = required(target.containerName, "Docker 容器目标缺少容器名称");
            return "docker inspect " + shellQuote(container) + " >/dev/null"
                    + " && docker exec " + shellQuote(container) + " sh -lc " + shellQuote(script);
        }
        return "sh -lc " + shellQuote(script);
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String strategy(AccessTarget target) {
        return target.targetType == TargetType.DOCKER_CONTAINER ? "docker-exec" : "ssh-host";
    }

    public record ShellResult(String stdout, String stderr, Integer exitStatus) {
    }
}
