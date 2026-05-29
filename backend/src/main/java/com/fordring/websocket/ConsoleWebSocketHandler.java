package com.fordring.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fordring.command.CommandExecution;
import com.fordring.command.CommandExecutionRunner;
import com.fordring.command.CommandService;
import com.fordring.common.enums.CommandSource;
import com.fordring.config.FordringProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
public class ConsoleWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final CommandExecutionRunner commandExecutionRunner;
    private final FordringProperties properties;

    public ConsoleWebSocketHandler(ObjectMapper objectMapper, CommandExecutionRunner commandExecutionRunner,
                                   FordringProperties properties) {
        this.objectMapper = objectMapper;
        this.commandExecutionRunner = commandExecutionRunner;
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
        commandExecutionRunner.start(new CommandService.ExecuteRequest(
                json.path("targetId").asLong(),
                command,
                json.path("timeoutSeconds").isMissingNode() ? properties.command.defaultTimeoutSeconds : json.path("timeoutSeconds").asInt(),
                CommandSource.valueOf(json.path("source").asText("MANUAL")),
                json.path("riskConfirmed").asBoolean(false),
                true
        ), "admin", new ConsoleListener(session, requestId));
    }

    private void stop(WebSocketSession session, JsonNode json) throws Exception {
        var requestId = json.path("requestId").asText();
        var executionId = json.path("executionId").asLong();
        send(session, "COMMAND_STOPPING", requestId, Map.of("executionId", executionId));
        var stopped = commandExecutionRunner.stop(executionId);
        if (stopped != null) {
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

    private class ConsoleListener implements CommandExecutionRunner.Listener {
        private final WebSocketSession session;
        private final String requestId;

        private ConsoleListener(WebSocketSession session, String requestId) {
            this.session = session;
            this.requestId = requestId;
        }

        @Override
        public void started(CommandExecution execution) {
            sendQuietly(session, "COMMAND_STARTED", requestId, Map.of("executionId", execution.id));
        }

        @Override
        public void output(Long executionId, String content) {
            sendQuietly(session, "COMMAND_OUTPUT", requestId, Map.of("executionId", executionId, "chunk", content));
        }

        @Override
        public void finished(CommandExecution execution) {
            sendQuietly(session, "COMMAND_FINISHED", requestId,
                    Map.of("executionId", execution.id, "status", execution.status.name(), "durationMs", execution.durationMs));
        }

        @Override
        public void failed(CommandExecution execution, String message) {
            sendQuietly(session, "COMMAND_FAILED", requestId,
                    Map.of("executionId", execution.id, "message", message));
        }

        @Override
        public void stopped(CommandExecution execution) {
            sendQuietly(session, "COMMAND_STOPPED", requestId, Map.of("executionId", execution.id));
        }
    }
}
