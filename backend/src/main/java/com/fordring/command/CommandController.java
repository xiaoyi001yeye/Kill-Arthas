package com.fordring.command;

import com.fordring.common.ApiResponse;
import com.fordring.common.PageResult;
import com.fordring.operator.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/commands")
public class CommandController {
    private final CommandService commandService;
    private final RiskService riskService;
    private final OperatorContext operatorContext;

    public CommandController(CommandService commandService, RiskService riskService, OperatorContext operatorContext) {
        this.commandService = commandService;
        this.riskService = riskService;
        this.operatorContext = operatorContext;
    }

    @PostMapping("/risk-check")
    public ApiResponse<RiskService.RiskCheckResult> riskCheck(@RequestBody RiskRequest request) {
        return ApiResponse.ok(riskService.check(request.command()));
    }

    @PostMapping("/executions")
    public ApiResponse<CommandService.ExecutionStarted> execute(@RequestBody CommandService.ExecuteRequest request,
                                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(commandService.executeSync(request, operatorContext.currentOperator(servletRequest)));
    }

    @GetMapping("/executions")
    public ApiResponse<PageResult<CommandExecutionDto>> list(@RequestParam(required = false) String keyword,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(commandService.list(keyword, page, pageSize));
    }

    @GetMapping("/executions/{id}")
    public ApiResponse<CommandExecutionDto> get(@PathVariable Long id) {
        return ApiResponse.ok(commandService.get(id));
    }

    @GetMapping("/executions/{id}/output")
    public ApiResponse<CommandService.OutputResult> output(@PathVariable Long id,
                                                           @RequestParam(defaultValue = "1") int fromSequence,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(commandService.output(id, fromSequence, limit));
    }

    @PostMapping("/executions/{id}/rerun")
    public ApiResponse<CommandExecutionDto> rerun(@PathVariable Long id, @RequestBody CommandService.ExecuteRequest request,
                                                  HttpServletRequest servletRequest) {
        return ApiResponse.ok(CommandExecutionDto.from(commandService.rerun(id, request,
                operatorContext.currentOperator(servletRequest))));
    }

    public record RiskRequest(Long targetId, String command) {
    }
}

