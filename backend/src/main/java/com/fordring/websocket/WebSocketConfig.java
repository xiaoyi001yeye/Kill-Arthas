package com.fordring.websocket;

import com.fordring.config.FordringProperties;
import com.fordring.standalone.StandaloneOriginHandshakeInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ConsoleWebSocketHandler handler;
    private final FordringProperties properties;
    private final StandaloneOriginHandshakeInterceptor standaloneOriginInterceptor;

    public WebSocketConfig(ConsoleWebSocketHandler handler, FordringProperties properties,
                           ObjectProvider<StandaloneOriginHandshakeInterceptor> standaloneOriginInterceptor) {
        this.handler = handler;
        this.properties = properties;
        this.standaloneOriginInterceptor = standaloneOriginInterceptor.getIfAvailable();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, "/ws/console");
        if (properties.standalone.enabled) {
            registration.addInterceptors(standaloneOriginInterceptor)
                    .setAllowedOriginPatterns("*");
        } else {
            registration.setAllowedOrigins(properties.cors.allowedOrigins.split(","));
        }
    }
}
