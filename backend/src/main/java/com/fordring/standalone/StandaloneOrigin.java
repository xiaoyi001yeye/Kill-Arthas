package com.fordring.standalone;

import java.net.URI;

final class StandaloneOrigin {
    private StandaloneOrigin() {
    }

    static boolean matches(String origin, URI requestUri) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            var originUri = URI.create(origin);
            return originUri.getHost() != null
                    && originUri.getHost().equalsIgnoreCase(requestUri.getHost())
                    && effectivePort(originUri) == effectivePort(requestUri);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase()) {
            case "https", "wss" -> 443;
            default -> 80;
        };
    }
}
