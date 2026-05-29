package com.fordring.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ArthasHttpClient {
    private static final int EXIT_HTTP_ERROR = 22;
    private static final int EXIT_CONNECT_ERROR = 23;
    private static final int EXIT_ARGUMENT_ERROR = 24;

    public static void main(String[] args) {
        try {
            execute();
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

    private static void execute() throws Exception {
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
