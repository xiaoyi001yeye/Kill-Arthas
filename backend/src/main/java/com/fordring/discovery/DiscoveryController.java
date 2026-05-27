package com.fordring.discovery;

import com.fordring.common.ApiResponse;
import com.fordring.common.enums.AuthType;
import com.fordring.common.enums.TargetType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final JavaProcessDiscoveryService javaProcessDiscoveryService;

    public DiscoveryController(JavaProcessDiscoveryService javaProcessDiscoveryService) {
        this.javaProcessDiscoveryService = javaProcessDiscoveryService;
    }

    @PostMapping("/containers")
    public ApiResponse<ContainerDiscoveryResult> containers(@RequestBody DiscoveryRequest request) {
        return ApiResponse.ok(new ContainerDiscoveryResult(request.host(), List.of(
                new ContainerInfo("8f3a9c", "order-service", "registry.example.com/order-service:1.2.3", "RUNNING"),
                new ContainerInfo("2ab91e", "gateway-app", "registry.example.com/gateway-app:2.1.0", "RUNNING")
        )));
    }

    @PostMapping("/java-processes")
    public ApiResponse<JavaProcessDiscoveryResult> javaProcesses(@RequestBody DiscoveryRequest request) {
        return ApiResponse.ok(javaProcessDiscoveryService.discover(request));
    }

    public record DiscoveryRequest(String host, Integer sshPort, AuthType authType, String username, Long credentialId,
                                   CredentialInput credential,
                                   TargetType targetType, String containerName) {
    }

    public record CredentialInput(String secret) {
    }

    public record ContainerDiscoveryResult(String host, List<ContainerInfo> containers) {
    }

    public record ContainerInfo(String containerId, String name, String image, String status) {
    }

    public record JavaProcessDiscoveryResult(String host, String containerName, List<JavaProcessInfo> processes,
                                             boolean manualInputAllowed) {
    }

    public record JavaProcessInfo(Long processId, String processName, String mainClass, String commandLine, String user) {
    }
}
