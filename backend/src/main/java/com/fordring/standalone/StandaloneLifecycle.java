package com.fordring.standalone;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StandaloneLifecycle {
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);

    public void beginShutdown() {
        acceptingRequests.set(false);
    }

    public void requireAcceptingRequests() {
        if (!acceptingRequests.get()) {
            throw new IllegalArgumentException("Fordring standalone 正在退出，不再接受新的接入或命令请求");
        }
    }
}
