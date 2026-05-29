package com.fordring.command;

import com.fordring.arthas.ArthasHttpCommandClient;
import com.fordring.common.enums.CommandStatus;
import com.fordring.config.FordringProperties;
import com.fordring.target.AccessTargetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

@Component
public class CommandExecutionRunner {
    private static final Logger log = LoggerFactory.getLogger(CommandExecutionRunner.class);

    private final CommandService commandService;
    private final AccessTargetService targetService;
    private final ArthasHttpCommandClient arthasHttpCommandClient;
    private final FordringProperties properties;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentMap<Long, RunningTask> runningTasks = new ConcurrentHashMap<>();

    public CommandExecutionRunner(CommandService commandService, AccessTargetService targetService,
                                  ArthasHttpCommandClient arthasHttpCommandClient, FordringProperties properties) {
        this.commandService = commandService;
        this.targetService = targetService;
        this.arthasHttpCommandClient = arthasHttpCommandClient;
        this.properties = properties;
    }

    public CommandExecution start(CommandService.ExecuteRequest request, String operatorName, Listener listener) {
        var timeoutSeconds = request.timeoutSeconds() == null
                ? properties.command.defaultTimeoutSeconds
                : request.timeoutSeconds();
        var execution = commandService.createExecution(request, operatorName);
        var target = targetService.get(execution.targetId);
        var arthasCommand = arthasHttpCommandClient.newCommand(target, execution.command, timeoutSeconds);
        log.info("Command execution scheduled executionId={} targetId={} source={} timeoutSeconds={} command={}",
                execution.id, execution.targetId, execution.source, timeoutSeconds, preview(execution.command));

        var task = new FutureTask<>(() -> {
            run(execution, arthasCommand, listener);
            return null;
        });
        runningTasks.put(execution.id, new RunningTask(task, arthasCommand));
        if (listener != null) {
            listener.started(execution);
        }
        executor.execute(task);
        return execution;
    }

    public CommandExecution rerun(Long originExecutionId, CommandService.ExecuteRequest override, String operatorName) {
        var request = commandService.rerunRequest(originExecutionId, override);
        log.info("Command rerun requested originExecutionId={} targetId={} timeoutSeconds={} command={}",
                originExecutionId, request.targetId(), request.timeoutSeconds(), preview(request.command()));
        return start(request, operatorName, null);
    }

    public CommandExecution stop(Long executionId) {
        var runningTask = runningTasks.get(executionId);
        if (runningTask != null) {
            log.info("Command execution stop requested executionId={}", executionId);
            runningTask.arthasCommand().interrupt();
            runningTask.future().cancel(true);
            return null;
        }
        log.info("Command execution stop requested for non-running task executionId={}", executionId);
        return commandService.finish(executionId, CommandStatus.STOPPED, null);
    }

    private void run(CommandExecution execution, ArthasHttpCommandClient.RunningCommand arthasCommand, Listener listener) {
        log.info("Command execution started executionId={} targetId={} command={}",
                execution.id, execution.targetId, preview(execution.command));
        try {
            arthasCommand.run((content) -> {
                commandService.appendOutput(execution.id, content);
                log.debug("Command execution output appended executionId={} bytes={}",
                        execution.id, content.getBytes(StandardCharsets.UTF_8).length);
                if (listener != null) {
                    listener.output(execution.id, content);
                }
            });
            var finished = commandService.finish(execution.id, CommandStatus.SUCCESS, null);
            log.info("Command execution succeeded executionId={} durationMs={} outputSizeBytes={}",
                    execution.id, finished.durationMs, finished.outputSizeBytes);
            if (listener != null) {
                listener.finished(finished);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var stopped = commandService.finish(execution.id, CommandStatus.STOPPED, null);
            log.info("Command execution stopped executionId={} durationMs={}", execution.id, stopped.durationMs);
            if (listener != null) {
                listener.stopped(stopped);
            }
        } catch (Exception error) {
            var message = errorMessage(error);
            if (isArthasUnavailable(message)) {
                message = "Arthas HTTP API 不可达，已将目标状态标记为已断开，请重新接入后再执行命令：" + message;
                targetService.markArthasDisconnected(execution.targetId,
                        "Arthas HTTP API 不可达，请重新接入后再执行命令");
            }
            var failed = commandService.finish(execution.id, CommandStatus.FAILED, message);
            log.warn("Command execution failed executionId={} durationMs={} message={}",
                    execution.id, failed.durationMs, message, error);
            if (listener != null) {
                listener.failed(failed, message);
            }
        } finally {
            runningTasks.remove(execution.id);
        }
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    private static String errorMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static boolean isArthasUnavailable(String message) {
        if (message == null) {
            return false;
        }
        var normalized = message.toLowerCase();
        return normalized.contains("connection refused")
                || normalized.contains("failed to connect")
                || normalized.contains("could not connect")
                || normalized.contains("connection reset")
                || normalized.contains("empty reply from server")
                || normalized.contains("connect timed out")
                || normalized.contains("connection timed out");
    }

    public interface Listener {
        default void started(CommandExecution execution) {
        }

        default void output(Long executionId, String content) {
        }

        default void finished(CommandExecution execution) {
        }

        default void failed(CommandExecution execution, String message) {
        }

        default void stopped(CommandExecution execution) {
        }
    }

    private record RunningTask(Future<?> future, ArthasHttpCommandClient.RunningCommand arthasCommand) {
    }
}
