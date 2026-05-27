package com.fordring.websocket;

import com.fordring.config.FordringProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ConsoleWebSocketHandler handler;
    private final FordringProperties properties;

    public WebSocketConfig(ConsoleWebSocketHandler handler, FordringProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/console")
                .setAllowedOrigins(properties.cors.allowedOrigins.split(","));
    }
}

