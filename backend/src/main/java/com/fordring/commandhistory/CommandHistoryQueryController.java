package com.fordring.commandhistory;

import com.fordring.common.ApiResponse;
import com.fordring.common.PageResult;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/command-history")
public class CommandHistoryQueryController {
    private final CommandHistoryQueryService queryService;

    public CommandHistoryQueryController(CommandHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/items")
    public ApiResponse<PageResult<CommandHistoryItemDto>> list(@RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "ALL") String origin,
                                                               @RequestParam(required = false) String sourceEnvironmentId,
                                                               @RequestParam(required = false) Long importBatchId,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(queryService.list(keyword, status, origin, sourceEnvironmentId, importBatchId, page, pageSize));
    }

    @GetMapping("/items/{historyId}")
    public ApiResponse<CommandHistoryItemDto> get(@PathVariable String historyId) {
        return ApiResponse.ok(queryService.get(historyId));
    }

    @GetMapping("/items/{historyId}/output")
    public ApiResponse<CommandHistoryQueryService.OutputResult> output(@PathVariable String historyId,
                                                                       @RequestParam(defaultValue = "1") int fromSequence,
                                                                       @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(queryService.output(historyId, fromSequence, limit));
    }
}
