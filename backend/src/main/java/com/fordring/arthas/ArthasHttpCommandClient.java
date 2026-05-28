package com.fordring.arthas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fordring.common.enums.TargetType;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTarget;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class ArthasHttpCommandClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FordringProperties properties;
    private final TargetShellExecutor shellExecutor;

    public ArthasHttpCommandClient(ObjectMapper objectMapper, FordringProperties properties, TargetShellExecutor shellExecutor) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.shellExecutor = shellExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public RunningCommand newCommand(AccessTarget target, String command, int timeoutSeconds) {
        return new RunningCommand(target, command, Math.max(timeoutSeconds, 1));
    }

    public class RunningCommand {
        private final AccessTarget target;
        private final String command;
        private final int timeoutSeconds;
        private final AtomicBoolean interruptRequested = new AtomicBoolean(false);
        private volatile String sessionId;
        private volatile String consumerId;

        private RunningCommand(AccessTarget target, String command, int timeoutSeconds) {
            this.target = target;
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
        }

        public void run(Consumer<String> outputConsumer) throws IOException, InterruptedException {
            var startedAt = Instant.now();
            try {
                var init = post(target, request("init_session"));
                requireState(init, "SUCCEEDED", "初始化 Arthas session 失败");
                sessionId = requiredText(init, "sessionId", "Arthas 未返回 sessionId");
                consumerId = requiredText(init, "consumerId", "Arthas 未返回 consumerId");

                emitResults(post(target, pullRequest()), outputConsumer, -1);

                var asyncExec = request("async_exec");
                asyncExec.put("sessionId", sessionId);
                asyncExec.put("command", command);
                var scheduled = post(target, asyncExec);
                requireScheduled(scheduled);
                var jobId = scheduled.path("body").path("jobId").asInt(-1);
                if (jobId < 0) {
                    throw new IllegalStateException("Arthas 未返回 jobId");
                }

                while (true) {
                    if (interruptRequested.get()) {
                        interruptQuietly();
                    }
                    var pulled = post(target, pullRequest());
                    emitResults(pulled, outputConsumer, jobId);
                    if (isTerminated(pulled, jobId)) {
                        return;
                    }
                    if (Duration.between(startedAt, Instant.now()).toSeconds() > timeoutSeconds) {
                        interruptQuietly();
                        throw new IllegalStateException("Arthas 命令执行超时，已发送中断请求");
                    }
                    Thread.sleep(POLL_INTERVAL.toMillis());
                }
            } finally {
                closeQuietly();
            }
        }

        public void interrupt() {
            interruptRequested.set(true);
            interruptQuietly();
        }

        private ObjectNode pullRequest() {
            var request = request("pull_results");
            request.put("sessionId", sessionId);
            request.put("consumerId", consumerId);
            return request;
        }

        private void interruptQuietly() {
            if (sessionId == null) {
                return;
            }
            try {
                var request = request("interrupt_job");
                request.put("sessionId", sessionId);
                post(target, request);
            } catch (Exception ignored) {
            }
        }

        private void closeQuietly() {
            if (sessionId == null) {
                return;
            }
            try {
                var request = request("close_session");
                request.put("sessionId", sessionId);
                post(target, request);
            } catch (Exception ignored) {
            }
        }
    }

    private JsonNode post(AccessTarget target, ObjectNode payload) throws IOException, InterruptedException {
        if (target.targetType == TargetType.DOCKER_CONTAINER) {
            return postViaTargetShell(target, payload);
        }
        return postDirect(target, payload);
    }

    private JsonNode postDirect(AccessTarget target, ObjectNode payload) throws IOException, InterruptedException {
        var uri = apiUri(target);
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        var authorization = authorizationHeader();
        if (authorization != null) {
            requestBuilder.header("Authorization", authorization);
        }
        var request = requestBuilder.build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            var message = error.getMessage() == null || error.getMessage().isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            throw new IllegalStateException("无法连接 Arthas HTTP API " + uri + "：" + message, error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Arthas HTTP API 返回状态码 " + response.statusCode());
        }
        return parseResponse("Arthas HTTP API " + uri, response.body());
    }

    private JsonNode postViaTargetShell(AccessTarget target, ObjectNode payload) throws IOException {
        requireHttpPort(target);
        var traceId = "arthas-api-" + UUID.randomUUID().toString().substring(0, 8);
        var script = arthasApiScript(target, objectMapper.writeValueAsString(payload));
        var command = shellExecutor.buildTargetCommand(target, script);
        TargetShellExecutor.ShellResult result;
        try {
            result = shellExecutor.execute(traceId, target, command, REQUEST_TIMEOUT.plusSeconds(5));
        } catch (IOException error) {
            var message = error.getMessage() == null || error.getMessage().isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            throw new IllegalStateException("无法通过 SSH/Docker Exec 调用 Arthas HTTP API 127.0.0.1:"
                    + target.httpPort + "/api：" + message, error);
        }
        if (result.exitStatus() == null || result.exitStatus() != 0) {
            throw new IllegalStateException("无法通过 SSH/Docker Exec 调用 Arthas HTTP API 127.0.0.1:"
                    + target.httpPort + "/api：" + commandFailureMessage(result));
        }
        return parseResponse("Arthas HTTP API 127.0.0.1:" + target.httpPort + "/api", result.stdout());
    }

    private String arthasApiScript(AccessTarget target, String payload) {
        var username = properties.arthas.username == null || properties.arthas.username.isBlank()
                ? "arthas"
                : properties.arthas.username;
        var password = properties.arthas.password == null ? "" : properties.arthas.password;
        return """
                set -e
                HTTP_PORT=__HTTP_PORT__
                ARTHAS_USERNAME=__ARTHAS_USERNAME__
                ARTHAS_PASSWORD=__ARTHAS_PASSWORD__
                PAYLOAD=__PAYLOAD__
                if ! command -v curl >/dev/null 2>&1; then
                  echo "CURL_MISSING"
                  exit 21
                fi
                if [ -n "$ARTHAS_PASSWORD" ]; then
                  curl -sS --connect-timeout 2 --max-time 15 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -u "$ARTHAS_USERNAME:$ARTHAS_PASSWORD" -d "$PAYLOAD"
                else
                  curl -sS --connect-timeout 2 --max-time 15 -X POST "http://127.0.0.1:$HTTP_PORT/api" -H 'Content-Type: application/json' -d "$PAYLOAD"
                fi
                """
                .replace("__HTTP_PORT__", target.httpPort.toString())
                .replace("__ARTHAS_USERNAME__", TargetShellExecutor.shellQuote(username))
                .replace("__ARTHAS_PASSWORD__", TargetShellExecutor.shellQuote(password))
                .replace("__PAYLOAD__", TargetShellExecutor.shellQuote(payload));
    }

    private JsonNode parseResponse(String endpoint, String responseBody) throws IOException {
        var body = objectMapper.readTree(responseBody == null ? "" : responseBody.trim());
        var state = body.path("state").asText();
        if ("FAILED".equals(state) || "REFUSED".equals(state)) {
            var message = body.path("message").asText(body.path("body").path("message").asText("Arthas 请求失败"));
            throw new IllegalStateException(message);
        }
        return body;
    }

    private URI apiUri(AccessTarget target) {
        if (target.host == null || target.host.isBlank()) {
            throw new IllegalArgumentException("目标缺少主机地址");
        }
        requireHttpPort(target);
        return URI.create("http://" + target.host + ":" + target.httpPort + "/api");
    }

    private void requireHttpPort(AccessTarget target) {
        if (target.httpPort == null) {
            throw new IllegalArgumentException("目标缺少 Arthas HTTP 端口");
        }
    }

    private String commandFailureMessage(TargetShellExecutor.ShellResult result) {
        if (result.stdout().contains("CURL_MISSING")) {
            return "目标环境未找到 curl，无法调用 Arthas HTTP API";
        }
        var output = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return preview(output);
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        var normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private String authorizationHeader() {
        if (properties.arthas.password == null || properties.arthas.password.isBlank()) {
            return null;
        }
        var username = properties.arthas.username == null || properties.arthas.username.isBlank()
                ? "arthas"
                : properties.arthas.username;
        var token = Base64.getEncoder().encodeToString((username + ":" + properties.arthas.password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private ObjectNode request(String action) {
        var request = objectMapper.createObjectNode();
        request.put("action", action);
        request.put("requestId", "fordring-" + UUID.randomUUID());
        return request;
    }

    private void requireState(JsonNode response, String expectedState, String message) {
        if (!expectedState.equals(response.path("state").asText())) {
            throw new IllegalStateException(message + "：" + response.path("state").asText("-"));
        }
    }

    private void requireScheduled(JsonNode response) {
        var state = response.path("state").asText();
        if (!"SCHEDULED".equals(state) && !"SUCCEEDED".equals(state)) {
            throw new IllegalStateException("Arthas 命令调度失败：" + state);
        }
    }

    private String requiredText(JsonNode response, String field, String message) {
        var value = response.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private void emitResults(JsonNode response, Consumer<String> outputConsumer, int commandJobId) throws IOException {
        var results = response.path("body").path("results");
        if (!results.isArray()) {
            return;
        }
        for (var result : results) {
            var formatted = formatResult(result, commandJobId);
            if (!formatted.isBlank()) {
                outputConsumer.accept(formatted);
            }
        }
    }

    private String formatResult(JsonNode result, int commandJobId) throws IOException {
        var type = result.path("type").asText();
        if ("input_status".equals(type)) {
            return "";
        }
        if ("command".equals(type)) {
            return "[arthas]$ " + result.path("command").asText() + "\n";
        }
        if ("message".equals(type)) {
            return result.path("message").asText() + "\n";
        }
        if ("welcome".equals(type)) {
            return "Welcome to Arthas"
                    + optional(" version ", result.path("version").asText())
                    + optional(" pid=", result.path("pid").asText())
                    + "\n";
        }
        if ("status".equals(type)) {
            var jobId = result.path("jobId").asInt(-1);
            if (commandJobId >= 0 && jobId != commandJobId) {
                return "";
            }
            var statusCode = result.path("statusCode").asInt();
            var message = result.path("message").asText();
            return statusCode == 0
                    ? "[arthas] 命令执行完成，statusCode=0\n"
                    : "[arthas] 命令执行失败，statusCode=" + statusCode + optional("，", message) + "\n";
        }
        if ("dashboard".equals(type)) {
            return formatDashboard(result);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
    }

    private String formatDashboard(JsonNode result) {
        var output = new StringBuilder();
        output.append("Dashboard\n");
        appendThreadTable(output, firstPresent(result, "threads", "threadInfos", "threadInfo"));
        appendMemoryTable(output, firstPresent(result, "memoryInfo", "memoryInfos", "memory"));
        appendGcTable(output, firstPresent(result, "gcInfo", "gcInfos", "garbageCollectors", "garbageCollectorInfos"));
        appendRuntimeTable(output, firstPresent(result, "runtimeInfo", "runtime"));
        if (output.length() == "Dashboard\n".length()) {
            return objectMapper.valueToTree(result).toPrettyString() + "\n";
        }
        return output.append('\n').toString();
    }

    private void appendThreadTable(StringBuilder output, JsonNode threads) {
        if (!threads.isArray() || threads.isEmpty()) {
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (var thread : threads) {
            rows.add(List.of(
                    text(thread, "id", "threadId"),
                    text(thread, "name", "threadName"),
                    text(thread, "group", "groupName"),
                    text(thread, "priority"),
                    text(thread, "state", "threadState"),
                    percentText(thread, "cpu", "cpuUsage"),
                    text(thread, "deltaTime", "deltaTimeMillis"),
                    text(thread, "time", "cpuTime"),
                    text(thread, "interrupted"),
                    text(thread, "daemon")
            ));
        }
        output.append('\n').append(table(List.of(
                "ID", "NAME", "GROUP", "PRIORITY", "STATE", "%CPU", "DELTA_TIME", "TIME", "INTERRUPTED", "DAEMON"
        ), rows));
    }

    private void appendMemoryTable(StringBuilder output, JsonNode memoryInfo) {
        var rows = new ArrayList<List<String>>();
        if (memoryInfo.isArray()) {
            for (var memory : memoryInfo) {
                rows.add(memoryRow(text(memory, "name", "pool", "memory"), memory));
            }
        } else if (memoryInfo.isObject()) {
            memoryInfo.fields().forEachRemaining((entry) -> {
                if (entry.getValue().isObject()) {
                    rows.add(memoryRow(entry.getKey(), entry.getValue()));
                }
            });
        }
        if (rows.isEmpty()) {
            return;
        }
        output.append('\n').append(table(List.of("Memory", "used", "total", "max", "usage"), rows));
    }

    private List<String> memoryRow(String name, JsonNode memory) {
        return List.of(
                valueOrDash(name),
                sizeText(memory, "used"),
                sizeText(memory, "total", "committed", "capacity"),
                sizeText(memory, "max"),
                usageText(memory, "usage")
        );
    }

    private void appendGcTable(StringBuilder output, JsonNode gcInfo) {
        var rows = new ArrayList<List<String>>();
        if (gcInfo.isArray()) {
            for (var gc : gcInfo) {
                rows.add(gcRow(text(gc, "name", "gcName"), gc));
            }
        } else if (gcInfo.isObject()) {
            gcInfo.fields().forEachRemaining((entry) -> {
                if (entry.getValue().isObject()) {
                    rows.add(gcRow(entry.getKey(), entry.getValue()));
                } else {
                    rows.add(List.of(entry.getKey(), entry.getValue().asText()));
                }
            });
        }
        if (rows.isEmpty()) {
            return;
        }
        output.append('\n').append(table(List.of("GC", "value"), rows));
    }

    private List<String> gcRow(String name, JsonNode gc) {
        var count = text(gc, "count", "collectionCount");
        var time = text(gc, "time", "collectionTime", "time(ms)", "collectionTimeMillis");
        var value = "-".equals(time) ? count : count + " / " + time + "ms";
        return List.of(valueOrDash(name), valueOrDash(value));
    }

    private void appendRuntimeTable(StringBuilder output, JsonNode runtimeInfo) {
        if (!runtimeInfo.isObject() || runtimeInfo.isEmpty()) {
            return;
        }
        var rows = new ArrayList<List<String>>();
        runtimeInfo.fields().forEachRemaining((entry) -> rows.add(List.of(entry.getKey(), entry.getValue().asText())));
        output.append('\n').append(table(List.of("Runtime", "value"), rows));
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return objectMapper.missingNode();
    }

    private String text(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (value.isMissingNode() || value.isNull()) {
            return "-";
        }
        return value.asText("-");
    }

    private String sizeText(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (value.isIntegralNumber()) {
            return humanBytes(value.asLong());
        }
        return text(node, fields);
    }

    private String usageText(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (!value.isNumber()) {
            return text(node, fields);
        }
        var number = value.asDouble();
        var percent = number <= 1 ? number * 100 : number;
        return String.format("%.2f%%", percent);
    }

    private String percentText(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (!value.isNumber()) {
            return text(node, fields);
        }
        return String.format("%.2f", value.asDouble());
    }

    private String table(List<String> headers, List<List<String>> rows) {
        var widths = new int[headers.size()];
        for (var index = 0; index < headers.size(); index++) {
            widths[index] = headers.get(index).length();
        }
        for (var row : rows) {
            for (var index = 0; index < row.size(); index++) {
                widths[index] = Math.max(widths[index], display(row.get(index)).length());
            }
        }
        var output = new StringBuilder();
        appendRow(output, headers, widths);
        for (var row : rows) {
            appendRow(output, row, widths);
        }
        return output.toString();
    }

    private void appendRow(StringBuilder output, List<String> values, int[] widths) {
        for (var index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append("  ");
            }
            output.append(padRight(display(values.get(index)), widths[index]));
        }
        output.append('\n');
    }

    private String display(String value) {
        return valueOrDash(value).replace('\n', ' ').replace('\r', ' ');
    }

    private String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private String humanBytes(long bytes) {
        if (bytes < 0) {
            return String.valueOf(bytes);
        }
        var units = List.of("B", "K", "M", "G", "T");
        var value = (double) bytes;
        var unitIndex = 0;
        while (value >= 1024 && unitIndex < units.size() - 1) {
            value /= 1024;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return bytes + units.get(unitIndex);
        }
        return String.format(value >= 10 ? "%.0f%s" : "%.1f%s", value, units.get(unitIndex));
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private boolean isTerminated(JsonNode response, int commandJobId) {
        var body = response.path("body");
        if (body.path("jobId").asInt(-2) == commandJobId && "TERMINATED".equals(body.path("jobStatus").asText())) {
            return true;
        }
        var results = body.path("results");
        if (!results.isArray()) {
            return false;
        }
        for (var result : results) {
            if ("status".equals(result.path("type").asText()) && result.path("jobId").asInt(-1) == commandJobId) {
                return true;
            }
        }
        return false;
    }

    private String optional(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }
}
