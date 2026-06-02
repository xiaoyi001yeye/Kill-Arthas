package com.fordring.standalone;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneOriginTest {
    private static final URI REQUEST = URI.create("ws://192.168.1.20:8080/ws/console");

    @Test
    void acceptsSameOriginAndDirectRequests() {
        assertTrue(StandaloneOrigin.matches("http://192.168.1.20:8080", REQUEST));
        assertTrue(StandaloneOrigin.matches(null, REQUEST));
    }

    @Test
    void rejectsThirdPartyOrigins() {
        assertFalse(StandaloneOrigin.matches("http://evil.example", REQUEST));
        assertFalse(StandaloneOrigin.matches("http://192.168.1.20:8081", REQUEST));
    }
}
