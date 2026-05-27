package com.fordring.target;

import com.fordring.arthas.ArthasInstallationService;
import com.fordring.common.ApiResponse;
import com.fordring.common.PageResult;
import com.fordring.operator.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/access-targets")
public class AccessTargetController {
    private final AccessTargetService service;
    private final ArthasInstallationService arthasInstallationService;
    private final OperatorContext operatorContext;

    public AccessTargetController(AccessTargetService service, ArthasInstallationService arthasInstallationService,
                                  OperatorContext operatorContext) {
        this.service = service;
        this.arthasInstallationService = arthasInstallationService;
        this.operatorContext = operatorContext;
    }

    @GetMapping("/stats")
    public ApiResponse<AccessTargetService.Stats> stats() {
        return ApiResponse.ok(service.stats());
    }

    @GetMapping
    public ApiResponse<PageResult<AccessTargetDto>> list(@RequestParam(required = false) String keyword,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(service.list(keyword, page, pageSize));
    }

    @PostMapping
    public ApiResponse<AccessTargetDto> create(@RequestBody AccessTargetService.CreateAccessTargetRequest request,
                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(service.create(request, operatorContext.currentOperator(servletRequest)));
    }

    @PutMapping("/{id}")
    public ApiResponse<AccessTargetDto> update(@PathVariable Long id,
                                               @RequestBody AccessTargetService.CreateAccessTargetRequest request,
                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(service.update(id, request, operatorContext.currentOperator(servletRequest)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest servletRequest) {
        service.delete(id, operatorContext.currentOperator(servletRequest));
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}")
    public ApiResponse<AccessTargetDto> get(@PathVariable Long id) {
        return ApiResponse.ok(AccessTargetDto.from(service.get(id)));
    }

    @PostMapping("/{id}/check")
    public ApiResponse<CheckResult> check(@PathVariable Long id, HttpServletRequest servletRequest) {
        var target = service.check(id, operatorContext.currentOperator(servletRequest));
        return ApiResponse.ok(CheckResult.success(target));
    }

    @PostMapping("/{id}/attach")
    public ApiResponse<AccessTargetDto> attach(@PathVariable Long id, @RequestBody(required = false) AttachRequest request,
                                               HttpServletRequest servletRequest) {
        var telnetPort = request == null ? null : request.telnetPort();
        var httpPort = request == null ? null : request.httpPort();
        return ApiResponse.ok(service.attach(id, telnetPort, httpPort, operatorContext.currentOperator(servletRequest)));
    }

    @PostMapping("/{id}/detach")
    public ApiResponse<AccessTargetDto> detach(@PathVariable Long id, HttpServletRequest servletRequest) {
        return ApiResponse.ok(service.detach(id, operatorContext.currentOperator(servletRequest)));
    }

    @PostMapping("/{id}/arthas/check-installation")
    public ApiResponse<ArthasInstallationService.Result> checkArthasInstallation(@PathVariable Long id,
                                                                                 HttpServletRequest servletRequest) {
        var target = service.get(id);
        return ApiResponse.ok(arthasInstallationService.check(target, operatorContext.currentOperator(servletRequest)));
    }

    @PostMapping("/{id}/arthas/install")
    public ApiResponse<ArthasInstallationService.Result> installArthas(@PathVariable Long id,
                                                                       HttpServletRequest servletRequest) {
        var target = service.get(id);
        return ApiResponse.ok(arthasInstallationService.install(target, operatorContext.currentOperator(servletRequest)));
    }

    public record AttachRequest(Integer telnetPort, Integer httpPort, Boolean forceRestart) {
    }

    public record CheckResult(Long targetId, boolean passed, java.util.List<Item> items) {
        static CheckResult success(AccessTargetDto target) {
            return new CheckResult(target.id(), true, java.util.List.of(
                    new Item("HOST_REACHABLE", "主机可连接", true, "模拟连接成功"),
                    new Item("JAVA_PROCESS_FOUND", "已发现 Java 进程", true, "PID " + target.processId()),
                    new Item("ATTACH_PERMISSION", "具备 attach 权限", true, "当前用户可访问目标进程")
            ));
        }
    }

    public record Item(String code, String name, boolean passed, String message) {
    }
}
