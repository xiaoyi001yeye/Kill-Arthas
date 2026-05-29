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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class ArthasHttpCommandClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);
    private static final double TRACE_HOT_NODE_PERCENT = 80.0;
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

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
        var authorization = authorizationHeader();
        return """
                set -e
                HTTP_PORT=__HTTP_PORT__
                PAYLOAD=__PAYLOAD__
                AUTHORIZATION_HEADER=__AUTHORIZATION_HEADER__
                FORDRING_HTTP_CLIENT_JAR=__FORDRING_HTTP_CLIENT_JAR__
                ARTHAS_URL="http://127.0.0.1:$HTTP_PORT/api"
                if command -v curl >/dev/null 2>&1; then
                  if [ -n "$AUTHORIZATION_HEADER" ]; then
                    curl -sS --connect-timeout 2 --max-time 15 -X POST "$ARTHAS_URL" -H 'Content-Type: application/json' -H "Authorization: $AUTHORIZATION_HEADER" -d "$PAYLOAD"
                  else
                    curl -sS --connect-timeout 2 --max-time 15 -X POST "$ARTHAS_URL" -H 'Content-Type: application/json' -d "$PAYLOAD"
                  fi
                  exit $?
                fi
                if command -v wget >/dev/null 2>&1; then
                  if [ -n "$AUTHORIZATION_HEADER" ]; then
                    wget -q -O - --timeout=15 --header='Content-Type: application/json' --header="Authorization: $AUTHORIZATION_HEADER" --post-data="$PAYLOAD" "$ARTHAS_URL"
                  else
                    wget -q -O - --timeout=15 --header='Content-Type: application/json' --post-data="$PAYLOAD" "$ARTHAS_URL"
                  fi
                  exit $?
                fi
                if [ -s "$FORDRING_HTTP_CLIENT_JAR" ]; then
                  if ! command -v java >/dev/null 2>&1; then
                    echo "JAVA_MISSING_FOR_HTTP_CLIENT"
                    exit 22
                  fi
                  export FORDRING_ARTHAS_URL="$ARTHAS_URL"
                  export FORDRING_ARTHAS_PAYLOAD="$PAYLOAD"
                  export FORDRING_ARTHAS_AUTHORIZATION="$AUTHORIZATION_HEADER"
                  export FORDRING_ARTHAS_CONNECT_TIMEOUT_MS=2000
                  export FORDRING_ARTHAS_READ_TIMEOUT_MS=15000
                  java -jar "$FORDRING_HTTP_CLIENT_JAR"
                  exit $?
                fi
                echo "FORDRING_HTTP_CLIENT_MISSING"
                exit 21
                """
                .replace("__HTTP_PORT__", target.httpPort.toString())
                .replace("__PAYLOAD__", TargetShellExecutor.shellQuote(payload))
                .replace("__AUTHORIZATION_HEADER__", TargetShellExecutor.shellQuote(authorization == null ? "" : authorization))
                .replace("__FORDRING_HTTP_CLIENT_JAR__", TargetShellExecutor.shellQuote(ArthasInstallationService.HTTP_CLIENT_JAR_TARGET_PATH));
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
        if (result.stdout().contains("FORDRING_HTTP_CLIENT_MISSING")) {
            return "目标环境未找到 curl/wget，且未发现 Fordring Arthas HTTP Client，请重新执行“安装 Arthas”后再试";
        }
        if (result.stdout().contains("JAVA_MISSING_FOR_HTTP_CLIENT")) {
            return "目标环境未找到 curl/wget，且无法用 java 执行 Fordring Arthas HTTP Client";
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
        if ("version".equals(type)) {
            return formatVersion(result);
        }
        if ("dashboard".equals(type)) {
            return formatDashboard(result);
        }
        if ("thread".equals(type)) {
            return formatThread(result);
        }
        if ("jvm".equals(type)) {
            return formatJvm(result);
        }
        if ("memory".equals(type)) {
            return formatMemory(result);
        }
        if ("enhancer".equals(type)) {
            return formatEnhancer(result);
        }
        if ("trace".equals(type)) {
            return formatTrace(result);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
    }

    private String formatVersion(JsonNode result) {
        var output = new StringBuilder();
        output.append("Arthas Version\n");
        var version = text(result, "version");
        if (!"-".equals(version)) {
            output.append("version  ").append(version).append("\n\n");
            return output.toString();
        }
        var rows = keyValueRows(result);
        if (!rows.isEmpty()) {
            output.append(table(List.of("Name", "value"), rows)).append('\n');
            return output.toString();
        }
        return objectMapper.valueToTree(result).toPrettyString() + "\n";
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

    private String formatThread(JsonNode result) {
        var output = new StringBuilder();
        output.append("Thread\n");
        appendThreadSummary(output, firstPresent(result, "threadStateCount", "threadStates", "stateCount"));
        appendThreadTable(output, firstPresent(result, "threadStats", "busyThreads", "threads", "threadInfos", "threadInfo"));
        appendThreadDetails(output, firstPresent(result, "busyThreads"));
        appendSingleThreadInfo(output, firstPresent(result, "threadInfo"));
        appendBlockingLockInfo(output, firstPresent(result, "blockingLockInfo"));
        return formattedOrJson(output, "Thread\n", result);
    }

    private String formatJvm(JsonNode result) {
        var output = new StringBuilder();
        output.append("JVM\n");
        var jvmInfo = firstPresent(result, "jvmInfo", "infos", "info", "data");
        appendJvmSection(output, "Runtime", result, jvmInfo, "runtimeInfo", "runtime", "RUNTIME");
        appendJvmSection(output, "Class Loading", result, jvmInfo, "classLoadingInfo", "classLoading", "CLASS-LOADING");
        appendJvmSection(output, "Compilation", result, jvmInfo, "compilationInfo", "compilation", "COMPILATION");
        appendJvmSection(output, "Garbage Collectors", result, jvmInfo, "garbageCollectors", "garbageCollectorInfos", "gcInfo", "GARBAGE-COLLECTORS");
        appendJvmSection(output, "Memory Managers", result, jvmInfo, "memoryManagers", "memoryManagerInfos", "MEMORY-MANAGERS");
        appendJvmSection(output, "Memory", result, jvmInfo, "memoryInfo", "memory", "MEMORY");
        appendJvmSection(output, "Operating System", result, jvmInfo, "operatingSystemInfo", "operatingSystem", "OPERATING-SYSTEM");
        appendJvmSection(output, "Thread", result, jvmInfo, "threadInfo", "thread", "THREAD");
        appendJvmSection(output, "File Descriptor", result, jvmInfo, "fileDescriptorInfo", "fileDescriptor", "FILE-DESCRIPTOR");
        appendJvmSection(output, "System Properties", result, jvmInfo, "systemProperties", "SYSTEM-PROPERTIES");
        appendJvmSection(output, "System Environment", result, jvmInfo, "systemEnvironment", "SYSTEM-ENVIRONMENT");
        appendJvmSection(output, "Input Arguments", result, jvmInfo, "inputArguments", "INPUT-ARGUMENTS");
        if (output.length() == "JVM\n".length()) {
            appendKeyValueSection(output, "Details", result);
        }
        if (output.length() == "JVM\n".length()) {
            output.append('\n').append("No JVM details returned\n");
        }
        return output.append('\n').toString();
    }

    private String formatMemory(JsonNode result) {
        var output = new StringBuilder();
        output.append("Memory\n");
        appendMemoryTable(output, firstPresent(result, "memoryInfo", "memoryInfos", "memory"));
        return formattedOrJson(output, "Memory\n", result);
    }

    private String formatEnhancer(JsonNode result) {
        var effect = firstPresent(result, "effect");
        var output = new StringBuilder();
        output.append("[arthas] trace listener attached");
        output.append(optional(", jobId=", text(result, "jobId")));
        if (effect.isObject()) {
            output.append(optional(", listenerId=", text(effect, "listenerId")));
            output.append(optional(", classes=", text(effect, "classCount")));
            output.append(optional(", methods=", text(effect, "methodCount")));
            output.append(optional(", enhanceCost=", durationText(effect, "cost")));
        }
        output.append('\n');
        return output.toString();
    }

    private String formatTrace(JsonNode result) {
        var root = firstPresent(result, "root");
        if (!root.isObject()) {
            return objectMapper.valueToTree(result).toPrettyString() + "\n";
        }

        var output = new StringBuilder();
        output.append("`---").append(traceThreadText(root)).append('\n');
        appendTraceNode(output, root, "    ", true, traceNodeTotalCostNanos(root), false);
        output.append('\n');
        return output.toString();
    }

    private String traceThreadText(JsonNode root) {
        return "ts=" + text(root, "ts", "timestamp", "timeStamp")
                + ";thread_name=" + text(root, "threadName", "thread_name", "name")
                + ";id=" + text(root, "threadId", "thread_id", "id")
                + ";is_daemon=" + text(root, "isDaemon", "is_daemon", "daemon")
                + ";priority=" + text(root, "priority")
                + ";TCCL=" + traceClassLoaderText(root);
    }

    private String traceClassLoaderText(JsonNode root) {
        var direct = firstPresent(root, "TCCL", "tccl", "contextClassLoader", "classLoader", "classLoaderName");
        if (!direct.isMissingNode() && !direct.isNull()) {
            return direct.isObject() ? compactObject(direct) : direct.asText("-");
        }
        var classLoaderHash = text(root, "classLoaderHash", "classloaderHash");
        if ("-".equals(classLoaderHash)) {
            return "-";
        }
        var classLoaderClass = text(root, "classLoaderClass", "classloaderClass");
        return "-".equals(classLoaderClass) ? classLoaderHash : classLoaderClass + "@" + classLoaderHash;
    }

    private void appendTraceNode(StringBuilder output, JsonNode node, String prefix, boolean last,
                                 double rootCostNanos, boolean includePercent) {
        var children = firstPresent(node, "children");
        output.append(prefix)
                .append(last ? "`---" : "+---")
                .append(traceCostBlock(node, rootCostNanos, includePercent))
                .append(' ')
                .append(traceMethodText(node))
                .append('\n');

        if (!children.isArray() || children.isEmpty()) {
            return;
        }
        var nextPrefix = prefix + (last ? "    " : "|   ");
        for (var index = 0; index < children.size(); index++) {
            appendTraceNode(output, children.get(index), nextPrefix, index == children.size() - 1,
                    rootCostNanos, true);
        }
    }

    private String traceMethodText(JsonNode node) {
        var className = text(node, "className");
        var methodName = text(node, "methodName");
        var lineNumber = text(node, "lineNumber");
        var location = "-".equals(lineNumber) || "-1".equals(lineNumber) ? "" : " #" + lineNumber;
        return className + ":" + methodName + "()" + location;
    }

    private String traceCostBlock(JsonNode node, double rootCostNanos, boolean includePercent) {
        var costNanos = traceNodeTotalCostNanos(node);
        var cost = traceCostDisplay(node);
        if (!includePercent || rootCostNanos <= 0 || costNanos < 0) {
            return "[" + cost + "]";
        }
        var percent = costNanos / rootCostNanos * 100;
        var value = String.format(Locale.ROOT, "[%.2f%% %s]", percent, cost);
        return percent >= TRACE_HOT_NODE_PERCENT ? ANSI_RED + value + ANSI_RESET : value;
    }

    private String traceCostDisplay(JsonNode node) {
        var count = traceCountText(node);
        if (!"-".equals(count) && !"1".equals(count) && hasAnyNumber(node, "minCost", "maxCost", "totalCost", "total")) {
            return "min=" + traceCostText(node, "minCost")
                    + ",max=" + traceCostText(node, "maxCost")
                    + ",total=" + traceCostText(node, "totalCost", "total")
                    + ",count=" + count;
        }
        return traceCostText(node, "cost", "totalCost", "total");
    }

    private String traceCountText(JsonNode node) {
        return text(node, "count", "times");
    }

    private boolean hasAnyNumber(JsonNode node, String... fields) {
        for (var field : fields) {
            if (firstPresent(node, field).isNumber()) {
                return true;
            }
        }
        return false;
    }

    private double traceNodeTotalCostNanos(JsonNode node) {
        var value = firstPresent(node, "totalCost", "total", "cost");
        return value.isNumber() ? value.asDouble() : -1;
    }

    private String traceCostText(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (!value.isNumber()) {
            return text(node, fields);
        }
        return String.format(Locale.ROOT, "%.6fms", value.asDouble() / 1_000_000.0);
    }

    private String formattedOrJson(StringBuilder output, String emptyValue, JsonNode result) {
        if (output.length() == emptyValue.length()) {
            return objectMapper.valueToTree(result).toPrettyString() + "\n";
        }
        return output.append('\n').toString();
    }

    private void appendThreadSummary(StringBuilder output, JsonNode stateCount) {
        if (!stateCount.isObject() || stateCount.isEmpty()) {
            return;
        }
        var rows = new ArrayList<List<String>>();
        var total = 0;
        stateCount.fields().forEachRemaining((entry) -> rows.add(List.of(entry.getKey(), entry.getValue().asText())));
        for (var row : rows) {
            try {
                total += Integer.parseInt(row.get(1));
            } catch (NumberFormatException ignored) {
            }
        }
        output.append('\n');
        if (total > 0) {
            output.append("Threads Total: ").append(total).append('\n');
        }
        output.append(table(List.of("State", "count"), rows));
    }

    private void appendThreadDetails(StringBuilder output, JsonNode threads) {
        if (!threads.isArray() || threads.isEmpty()) {
            return;
        }
        var detail = new StringBuilder();
        for (var thread : threads) {
            var stackTrace = firstPresent(thread, "stackTrace", "stackTraces");
            if (!stackTrace.isArray() || stackTrace.isEmpty()) {
                continue;
            }
            detail.append('\n')
                    .append('"').append(text(thread, "name", "threadName")).append('"')
                    .append(" Id=").append(text(thread, "id", "threadId"))
                    .append(' ').append(text(thread, "state", "threadState"))
                    .append(optional(" cpuUsage=", percentText(thread, "cpu", "cpuUsage")))
                    .append('\n');
            appendStackTrace(detail, stackTrace);
        }
        if (!detail.isEmpty()) {
            output.append(detail);
        }
    }

    private void appendSingleThreadInfo(StringBuilder output, JsonNode threadInfo) {
        if (!threadInfo.isObject() || threadInfo.isEmpty()) {
            return;
        }
        output.append('\n')
                .append('"').append(text(threadInfo, "threadName", "name")).append('"')
                .append(" Id=").append(text(threadInfo, "threadId", "id"))
                .append(' ').append(text(threadInfo, "threadState", "state"))
                .append(optional(" on ", text(threadInfo, "lockName")))
                .append('\n');
        appendStackTrace(output, firstPresent(threadInfo, "stackTrace", "stackTraces"));
    }

    private void appendBlockingLockInfo(StringBuilder output, JsonNode blockingLockInfo) {
        if (!blockingLockInfo.isObject() || blockingLockInfo.isEmpty()) {
            return;
        }
        output.append('\n').append("Blocking Thread\n");
        appendKeyValueSection(output, "Lock", blockingLockInfo);
    }

    private void appendThreadTable(StringBuilder output, JsonNode threads) {
        var rows = threadRows(threads);
        if (rows.isEmpty()) {
            return;
        }
        output.append('\n').append(table(List.of(
                "ID", "NAME", "GROUP", "PRIORITY", "STATE", "%CPU", "DELTA_TIME", "TIME", "INTERRUPTED", "DAEMON"
        ), rows));
    }

    private List<List<String>> threadRows(JsonNode threads) {
        var rows = new ArrayList<List<String>>();
        if (threads.isArray()) {
            for (var thread : threads) {
                rows.add(threadRow(thread));
            }
        } else if (threads.isObject() && !threads.isEmpty()) {
            rows.add(threadRow(threads));
        }
        return rows;
    }

    private List<String> threadRow(JsonNode thread) {
        return List.of(
                text(thread, "id", "threadId"),
                text(thread, "name", "threadName"),
                text(thread, "group", "groupName", "threadGroupName"),
                text(thread, "priority"),
                text(thread, "state", "threadState"),
                percentText(thread, "cpu", "cpuUsage"),
                durationText(thread, "deltaTime", "deltaTimeMillis"),
                durationText(thread, "time", "cpuTime"),
                text(thread, "interrupted"),
                text(thread, "daemon")
        );
    }

    private void appendStackTrace(StringBuilder output, JsonNode stackTrace) {
        if (!stackTrace.isArray() || stackTrace.isEmpty()) {
            return;
        }
        for (var frame : stackTrace) {
            if (frame.isObject()) {
                output.append("    at ")
                        .append(text(frame, "className", "declaringClass"))
                        .append('.')
                        .append(text(frame, "methodName", "method"))
                        .append('(')
                        .append(stackFrameLocation(frame))
                        .append(")\n");
            } else {
                output.append("    at ").append(frame.asText()).append('\n');
            }
        }
    }

    private String stackFrameLocation(JsonNode frame) {
        var fileName = text(frame, "fileName", "file");
        var lineNumber = text(frame, "lineNumber", "line");
        if ("-".equals(fileName)) {
            return "-".equals(lineNumber) ? "Unknown Source" : "Unknown Source:" + lineNumber;
        }
        return "-".equals(lineNumber) ? fileName : fileName + ":" + lineNumber;
    }

    private void appendKeyValueSection(StringBuilder output, String title, JsonNode value) {
        var rows = keyValueRows(value);
        if (rows.isEmpty()) {
            return;
        }
        output.append('\n').append(title).append('\n').append(table(List.of("Name", "value"), rows));
    }

    private void appendJvmSection(StringBuilder output, String title, JsonNode result, JsonNode jvmInfo, String... fields) {
        appendKeyValueSection(output, title, firstJvmGroup(result, jvmInfo, fields));
    }

    private JsonNode firstJvmGroup(JsonNode result, JsonNode jvmInfo, String... fields) {
        var value = firstPresent(result, fields);
        if (!value.isMissingNode() && !value.isNull()) {
            return value;
        }
        if (jvmInfo.isObject()) {
            for (var field : fields) {
                var direct = jvmInfo.path(field);
                if (!direct.isMissingNode() && !direct.isNull()) {
                    return direct;
                }
                var normalizedField = normalizeJvmGroup(field);
                var iterator = jvmInfo.fields();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (normalizeJvmGroup(entry.getKey()).equals(normalizedField)) {
                        return entry.getValue();
                    }
                }
            }
        }
        if (jvmInfo.isArray()) {
            var rows = objectMapper.createArrayNode();
            for (var item : jvmInfo) {
                var group = text(item, "group", "section", "category");
                for (var field : fields) {
                    if (normalizeJvmGroup(group).equals(normalizeJvmGroup(field))) {
                        rows.add(item);
                        break;
                    }
                }
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return objectMapper.missingNode();
    }

    private String normalizeJvmGroup(String value) {
        return valueOrDash(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private List<List<String>> keyValueRows(JsonNode value) {
        var rows = new ArrayList<List<String>>();
        if (value.isObject()) {
            value.fields().forEachRemaining((entry) -> {
                if (ignoreKeyValueField(entry.getKey())) {
                    return;
                }
                if (entry.getValue().isObject()) {
                    rows.add(List.of(entry.getKey(), compactObject(entry.getValue())));
                } else if (entry.getValue().isArray()) {
                    appendArrayRows(rows, entry.getKey(), entry.getValue());
                } else {
                    rows.add(List.of(entry.getKey(), scalarText(entry.getValue())));
                }
            });
        } else if (value.isArray()) {
            appendArrayRows(rows, "-", value);
        }
        return rows;
    }

    private boolean ignoreKeyValueField(String field) {
        return "type".equals(field) || "jobId".equals(field) || "group".equals(field)
                || "section".equals(field) || "category".equals(field);
    }

    private void appendArrayRows(List<List<String>> rows, String prefix, JsonNode values) {
        for (var index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var name = text(value, "name", "managerName", "gcName", "memoryManagerName");
            if ("-".equals(name)) {
                name = "-".equals(prefix) ? String.valueOf(index + 1) : prefix + "[" + index + "]";
            }
            rows.add(List.of(name, value.isObject() ? compactObject(value) : scalarText(value)));
        }
    }

    private String compactObject(JsonNode value) {
        var directValue = firstPresent(value, "value");
        if (!directValue.isMissingNode() && !directValue.isNull()) {
            if (directValue.isObject()) {
                return compactObject(directValue);
            }
            if (directValue.isArray()) {
                return arrayText(directValue);
            }
            return scalarText(directValue);
        }
        var parts = new ArrayList<String>();
        value.fields().forEachRemaining((entry) -> {
            if (ignoreKeyValueField(entry.getKey()) || "name".equals(entry.getKey())) {
                return;
            }
            parts.add(entry.getKey() + "=" + scalarText(entry.getValue()));
        });
        if (parts.isEmpty()) {
            var name = text(value, "name", "managerName", "gcName", "memoryManagerName");
            return "-".equals(name) ? "-" : name;
        }
        return String.join(", ", parts);
    }

    private String scalarText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "-";
        }
        if (value.isIntegralNumber()) {
            return String.valueOf(value.asLong());
        }
        if (value.isNumber() || value.isBoolean() || value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            return arrayText(value);
        }
        if (value.isObject()) {
            return compactObject(value);
        }
        return value.asText("-");
    }

    private String arrayText(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "-";
        }
        var parts = new ArrayList<String>();
        for (var value : values) {
            parts.add(value.isObject() ? compactObject(value) : scalarText(value));
        }
        return String.join(", ", parts);
    }

    private String durationText(JsonNode node, String... fields) {
        var value = firstPresent(node, fields);
        if (!value.isIntegralNumber()) {
            return text(node, fields);
        }
        var millis = value.asLong();
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format("%.3fs", millis / 1000.0);
    }

    private void appendMemoryRows(ArrayList<List<String>> rows, String group, JsonNode memoryInfo) {
        if (memoryInfo.isArray()) {
            for (var memory : memoryInfo) {
                rows.add(memoryRow(memoryName(group, memory), memory));
            }
        } else if (memoryInfo.isObject()) {
            rows.add(memoryRow(group, memoryInfo));
        }
    }

    private String memoryName(String group, JsonNode memory) {
        var name = text(memory, "name", "pool", "memory");
        if ("-".equals(name)) {
            return valueOrDash(group);
        }
        return "-".equals(valueOrDash(group)) || group.equals(name) ? name : group + "/" + name;
    }

    private void appendMemoryTable(StringBuilder output, JsonNode memoryInfo) {
        var rows = new ArrayList<List<String>>();
        if (memoryInfo.isArray()) {
            for (var memory : memoryInfo) {
                rows.add(memoryRow(text(memory, "name", "pool", "memory"), memory));
            }
        } else if (memoryInfo.isObject()) {
            memoryInfo.fields().forEachRemaining((entry) -> {
                appendMemoryRows(rows, entry.getKey(), entry.getValue());
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
                memoryUsageText(memory)
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

    private String memoryUsageText(JsonNode node) {
        var usage = firstPresent(node, "usage");
        if (usage.isNumber()) {
            return usageText(node, "usage");
        }
        var used = firstPresent(node, "used");
        var max = firstPresent(node, "max");
        var total = firstPresent(node, "total", "committed", "capacity");
        var denominator = max.isIntegralNumber() && max.asLong() > 0 ? max : total;
        if (!used.isIntegralNumber() || !denominator.isIntegralNumber() || denominator.asLong() <= 0) {
            return "-";
        }
        return String.format("%.2f%%", used.asDouble() / denominator.asDouble() * 100);
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
            return "-";
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
        return value == null || value.isBlank() || "-".equals(value) ? "" : prefix + value;
    }
}
