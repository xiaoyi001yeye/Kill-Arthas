package com.fordring.standalone;

import com.fordring.config.FordringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

@Component
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneStartupLogger {
    private static final Logger log = LoggerFactory.getLogger(StandaloneStartupLogger.class);
    private final FordringProperties properties;
    private final Environment environment;

    public StandaloneStartupLogger(FordringProperties properties, Environment environment,
                                   StandaloneInstanceInitializer instanceInitializer) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener
    public void started(WebServerInitializedEvent event) {
        var port = event.getWebServer().getPort();
        log.warn("WARNING: Fordring standalone is running without application login.");
        log.warn("WARNING: HTTP and WebSocket traffic are not encrypted.");
        log.warn("WARNING: Only expose this port inside a trusted development network.");
        log.warn("WARNING: Stop the process after diagnostics are complete.");
        var address = environment.getProperty("server.address", "0.0.0.0");
        log.info("Fordring standalone listening on {}:{}", address, port);
        log.info("Fordring standalone instance id: {}", properties.instance.id);
        log.info("Fordring standalone instance name: {}", properties.instance.name);
        log.info("http://127.0.0.1:{}", port);
        if (!"127.0.0.1".equals(address) && !"localhost".equalsIgnoreCase(address)) {
            printLanAddresses(port);
        }
        warnIfTempDirectoryIsNotWritable();
    }

    private void printLanAddresses(int port) {
        try {
            for (var networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (var address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        log.info("http://{}:{}", address.getHostAddress(), port);
                    }
                }
            }
        } catch (Exception error) {
            log.warn("Unable to enumerate LAN addresses: {}", error.getMessage());
        }
    }

    private void warnIfTempDirectoryIsNotWritable() {
        var tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.isWritable(tempDirectory)) {
            log.warn("Java temporary directory is not writable: {}. SSH key authentication and offline Arthas installation may fail.",
                    tempDirectory);
        }
    }
}
