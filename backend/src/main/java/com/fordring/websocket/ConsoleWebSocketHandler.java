package com.fordring.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fordring.arthas.ArthasHttpCommandClient;
import com.fordring.command.CommandService;
import com.fordring.common.enums.CommandSource;
import com.fordring.common.enums.CommandStatus;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTargetService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.*;

@Component
public class ConsoleWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final CommandService commandService;
    private final AccessTargetService targetService;
    private final ArthasHttpCommandClient arthasHttpCommandClient;
    private final FordringProperties properties;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentMap<Long, RunningTask> runningTasks = new ConcurrentHashMap<>();

    public ConsoleWebSocketHandler(ObjectMapper objectMapper, CommandService commandService, AccessTargetService targetService,
                                   ArthasHttpCommandClient arthasHttpCommandClient, FordringProperties properties) {
        this.objectMapper = objectMapper;
        this.commandService = commandService;
        this.targetService = targetService;
        this.arthasHttpCommandClient = arthasHttpCommandClient;
        this.properties = properties;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var json = objectMapper.readTree(message.getPayload());
        var type = json.path("type").asText();
        if ("EXECUTE_COMMAND".equals(type)) {
            execute(session, json);
        } else if ("STOP_COMMAND".equals(type)) {
            stop(session, json);
        }
    }

    private void execute(WebSocketSession session, JsonNode json) throws Exception {
        var requestId = json.path("requestId").asText();
        var command = json.path("command").asText();
        var execution = commandService.createExecution(new CommandService.ExecuteRequest(
                json.path("targetId").asLong(),
                command,
                json.path("timeoutSeconds").isMissingNode() ? properties.command.defaultTimeoutSeconds : json.path("timeoutSeconds").asInt(),
                CommandSource.valueOf(json.path("source").asText("MANUAL")),
                json.path("riskConfirmed").asBoolean(false),
                true
        ), "admin");
        send(session, "COMMAND_STARTED", requestId, Map.of("executionId", execution.id));
        var target = targetService.get(execution.targetId);
        var arthasCommand = arthasHttpCommandClient.newCommand(target, command,
                json.path("timeoutSeconds").isMissingNode() ? properties.command.defaultTimeoutSeconds : json.path("timeoutSeconds").asInt());
        var task = executor.submit(() -> {
            try {
                arthasCommand.run((content) -> {
                    commandService.appendOutput(execution.id, content);
                    sendQuietly(session, "COMMAND_OUTPUT", requestId, Map.of("executionId", execution.id, "chunk", content));
                });
                var finished = commandService.finish(execution.id, CommandStatus.SUCCESS, null);
                sendQuietly(session, "COMMAND_FINISHED", requestId,
                        Map.of("executionId", execution.id, "status", finished.status.name(), "durationMs", finished.durationMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                commandService.finish(execution.id, CommandStatus.STOPPED, null);
                sendQuietly(session, "COMMAND_STOPPED", requestId, Map.of("executionId", execution.id));
            } catch (Exception error) {
                var message = errorMessage(error);
                commandService.finish(execution.id, CommandStatus.FAILED, message);
                sendQuietly(session, "COMMAND_FAILED", requestId,
                        Map.of("executionId", execution.id, "message", message));
            } finally {
                runningTasks.remove(execution.id);
            }
        });
        runningTasks.put(execution.id, new RunningTask(task, arthasCommand));
    }

    private void stop(WebSocketSession session, JsonNode json) throws Exception {
        var requestId = json.path("requestId").asText();
        var executionId = json.path("executionId").asLong();
        send(session, "COMMAND_STOPPING", requestId, Map.of("executionId", executionId));
        var runningTask = runningTasks.get(executionId);
        if (runningTask != null) {
            runningTask.arthasCommand().interrupt();
            runningTask.future().cancel(true);
        } else {
            commandService.finish(executionId, CommandStatus.STOPPED, null);
            send(session, "COMMAND_STOPPED", requestId, Map.of("executionId", executionId));
        }
    }

    private void send(WebSocketSession session, String type, String requestId, Map<String, Object> payload) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("requestId", requestId);
        body.putAll(payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(body)));
    }

    private void sendQuietly(WebSocketSession session, String type, String requestId, Map<String, Object> payload) {
        try {
            if (session.isOpen()) {
                send(session, type, requestId, payload);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // MVP keeps the persisted command history even if the browser leaves the console.
    }

    private static String errorMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private record RunningTask(Future<?> future, ArthasHttpCommandClient.RunningCommand arthasCommand) {
    }
}
