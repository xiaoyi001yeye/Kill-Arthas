package com.fordring.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipInputStream;

public class ArthasHttpClient {
    private static final int EXIT_HTTP_ERROR = 22;
    private static final int EXIT_CONNECT_ERROR = 23;
    private static final int EXIT_ARGUMENT_ERROR = 24;

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(EXIT_ARGUMENT_ERROR);
        } catch (IOException error) {
            System.err.println(error.getClass().getSimpleName() + ": " + message(error));
            System.exit(EXIT_CONNECT_ERROR);
        } catch (Exception error) {
            System.err.println(error.getClass().getSimpleName() + ": " + message(error));
            System.exit(EXIT_CONNECT_ERROR);
        }
    }

    private static void execute(String[] args) throws Exception {
        if (args.length > 0) {
            executeTool(args);
            return;
        }
        executeHttpRequest();
    }

    private static void executeTool(String[] args) throws Exception {
        if (args.length == 2 && "--sha256".equals(args[0])) {
            System.out.println(sha256(Path.of(args[1])));
            return;
        }
        if (args.length == 3 && "--extract-zip".equals(args[0])) {
            extractZip(Path.of(args[1]), Path.of(args[2]));
            System.out.println("FORDRING_ARTHAS_ZIP_EXTRACTED");
            return;
        }
        throw new IllegalArgumentException("Expected no arguments, --sha256 <file>, or --extract-zip <zip> <directory>");
    }

    private static void executeHttpRequest() throws Exception {
        String url = env("FORDRING_ARTHAS_URL");
        String payload = env("FORDRING_ARTHAS_PAYLOAD");
        String authorization = System.getenv("FORDRING_ARTHAS_AUTHORIZATION");
        int connectTimeout = intEnv("FORDRING_ARTHAS_CONNECT_TIMEOUT_MS", 2_000);
        int readTimeout = intEnv("FORDRING_ARTHAS_READ_TIMEOUT_MS", 15_000);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!isBlank(authorization)) {
            connection.setRequestProperty("Authorization", authorization);
        }

        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input != null) {
            try (InputStream response = input) {
                copy(response, System.out);
            }
        }
        if (status < 200 || status >= 300) {
            System.exit(EXIT_HTTP_ERROR);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte current : digest.digest()) {
            value.append(String.format("%02x", current));
        }
        return value.toString();
    }

    private static void extractZip(Path zip, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDestination);
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = normalizedDestination.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedDestination)) {
                    throw new IOException("ZIP entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                input.closeEntry();
            }
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (isBlank(value)) {
            throw new IllegalArgumentException("Missing env " + name);
        }
        return value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer env " + name + "=" + value);
        }
    }

    private static String message(Throwable error) {
        return isBlank(error.getMessage())
                ? error.getClass().getName()
                : error.getMessage();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
