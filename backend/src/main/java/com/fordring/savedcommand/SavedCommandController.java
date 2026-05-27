package com.fordring.savedcommand;

import com.fordring.common.ApiResponse;
import com.fordring.operator.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/saved-commands")
public class SavedCommandController {
    private final SavedCommandRepository repository;
    private final OperatorContext operatorContext;

    public SavedCommandController(SavedCommandRepository repository, OperatorContext operatorContext) {
        this.repository = repository;
        this.operatorContext = operatorContext;
    }

    @GetMapping
    public ApiResponse<SavedCommandsResult> list(@RequestParam(required = false) Boolean visibleInConsole) {
        var commands = (visibleInConsole == null ? repository.findAll() : repository.findByVisibleInConsole(visibleInConsole))
                .stream()
                .map(SavedCommandDto::from)
                .toList();
        return ApiResponse.ok(new SavedCommandsResult(commands));
    }

    @PostMapping
    public ApiResponse<SavedCommandDto> create(@RequestBody SaveRequest request, HttpServletRequest servletRequest) {
        var now = Instant.now();
        var command = new SavedCommand();
        command.name = request.name();
        command.command = request.command();
        command.description = request.description();
        command.visibleInConsole = Boolean.TRUE.equals(request.visibleInConsole());
        command.operatorName = operatorContext.currentOperator(servletRequest);
        command.createdAt = now;
        command.updatedAt = now;
        return ApiResponse.ok(SavedCommandDto.from(repository.save(command)));
    }

    @PutMapping("/{id}")
    public ApiResponse<SavedCommandDto> update(@PathVariable Long id, @RequestBody SaveRequest request) {
        var command = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("保存命令不存在"));
        command.name = request.name();
        command.command = request.command();
        command.description = request.description();
        command.visibleInConsole = Boolean.TRUE.equals(request.visibleInConsole());
        command.updatedAt = Instant.now();
        return ApiResponse.ok(SavedCommandDto.from(repository.save(command)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ApiResponse.ok(null);
    }

    public record SaveRequest(String name, String command, String description, Boolean visibleInConsole) {
    }

    public record SavedCommandsResult(List<SavedCommandDto> items) {
    }
}

