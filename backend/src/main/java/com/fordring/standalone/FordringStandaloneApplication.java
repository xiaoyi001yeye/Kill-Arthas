package com.fordring.standalone;

import com.fordring.FordringApplication;
import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

public final class FordringStandaloneApplication {
    private static final SecureRandom RANDOM = new SecureRandom();

    private FordringStandaloneApplication() {
    }

    public static void main(String[] args) {
        var effectiveArgs = new ArrayList<>(Arrays.asList(args));
        addGeneratedInstanceDefaults(effectiveArgs);
        addSelectedPort(effectiveArgs);
        addLoggingConfig(effectiveArgs);

        var application = new SpringApplication(FordringApplication.class);
        application.setAdditionalProfiles("standalone");
        application.run(effectiveArgs.toArray(String[]::new));
    }

    private static void addGeneratedInstanceDefaults(List<String> args) {
        var hostname = hostname();
        if (!configured(args, "--fordring.instance.id=", "FORDRING_INSTANCE_ID")) {
            var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            var random = new byte[2];
            RANDOM.nextBytes(random);
            System.setProperty("FORDRING_INSTANCE_ID",
                    "standalone-" + hostname + "-" + timestamp + "-" + HexFormat.of().formatHex(random));
        }
        if (!configured(args, "--fordring.instance.name=", "FORDRING_INSTANCE_NAME")) {
            System.setProperty("FORDRING_INSTANCE_NAME", "standalone-" + hostname);
        }
    }

    private static void addSelectedPort(List<String> args) {
        if (configured(args, "--server.port=", "FORDRING_SERVER_PORT")) {
            return;
        }
        for (var port = 8080; port <= 8099; port++) {
            if (available(port)) {
                System.setProperty("FORDRING_SERVER_PORT", String.valueOf(port));
                return;
            }
        }
        throw new IllegalStateException("Fordring standalone could not find a free port in 8080-8099. Use --server.port explicitly.");
    }

    private static void addLoggingConfig(List<String> args) {
        if (args.stream().anyMatch(value -> value.startsWith("--logging.config="))) {
            return;
        }
        var dataDir = System.getenv().getOrDefault("FORDRING_DATA_DIR", "./fordring-data");
        var logDir = Path.of(dataDir).resolve("logs").toAbsolutePath().normalize();
        try {
            Files.createDirectories(logDir);
            if (!Files.isWritable(logDir)) {
                throw new IOException("directory is not writable");
            }
            System.setProperty("FORDRING_LOG_PATH", logDir.toString());
            args.add("--logging.config=classpath:logback-standalone-spring.xml");
        } catch (IOException error) {
            System.err.println("WARNING: Fordring standalone log directory is not writable: " + logDir);
            System.err.println("WARNING: Falling back to console-only logging.");
            args.add("--logging.config=classpath:logback-standalone-console-spring.xml");
        }
    }

    private static boolean configured(List<String> args, String argumentPrefix, String environmentName) {
        return args.stream().anyMatch(value -> value.startsWith(argumentPrefix))
                || System.getenv(environmentName) != null
                || System.getProperty(argumentPrefix.substring(2, argumentPrefix.length() - 1)) != null;
    }

    private static boolean available(int port) {
        try (var socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "-");
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }
}
