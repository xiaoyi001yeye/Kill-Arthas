package com.fordring.standalone;

import com.fordring.command.CommandExecutionRunner;
import com.fordring.target.AccessTargetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneShutdownCleaner {
    private static final Logger log = LoggerFactory.getLogger(StandaloneShutdownCleaner.class);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(15);

    private final StandaloneLifecycle lifecycle;
    private final StandaloneArthasOwnershipTracker ownershipTracker;
    private final CommandExecutionRunner commandExecutionRunner;
    private final AccessTargetService targetService;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);

    public StandaloneShutdownCleaner(StandaloneLifecycle lifecycle,
                                     StandaloneArthasOwnershipTracker ownershipTracker,
                                     CommandExecutionRunner commandExecutionRunner,
                                     AccessTargetService targetService) {
        this.lifecycle = lifecycle;
        this.ownershipTracker = ownershipTracker;
        this.commandExecutionRunner = commandExecutionRunner;
        this.targetService = targetService;
    }

    @EventListener
    public void cleanup(ContextClosedEvent ignored) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        lifecycle.beginShutdown();
        commandExecutionRunner.stopAll();

        var targets = ownershipTracker.snapshot();
        if (targets.isEmpty()) {
            return;
        }
        log.info("Fordring standalone shutdown cleanup detaching targets={}", targets);
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("fordring-standalone-shutdown-", 0)
                .factory());
        var deadline = Instant.now().plus(CLEANUP_TIMEOUT);
        var futures = targets.stream()
                .collect(java.util.stream.Collectors.toMap(
                        targetId -> targetId,
                        targetId -> executor.submit(() -> targetService.detach(targetId, "standalone-shutdown"))
                ));
        var failed = new ArrayList<Long>();
        for (var entry : futures.entrySet()) {
            var remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) {
                failed.add(entry.getKey());
                continue;
            }
            try {
                entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                failed.add(entry.getKey());
                log.warn("Fordring standalone shutdown detach failed targetId={} message={}",
                        entry.getKey(), error.getMessage());
            }
        }
        executor.shutdownNow();
        if (!failed.isEmpty()) {
            log.warn("Fordring standalone exited with targets not detached: {}", failed);
        }
    }
}
