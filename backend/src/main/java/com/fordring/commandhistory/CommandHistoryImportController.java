package com.fordring.commandhistory;

import com.fordring.common.ApiResponse;
import com.fordring.operator.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/command-history")
public class CommandHistoryImportController {
    private final CommandHistoryImportService importService;
    private final OperatorContext operatorContext;

    public CommandHistoryImportController(CommandHistoryImportService importService, OperatorContext operatorContext) {
        this.importService = importService;
        this.operatorContext = operatorContext;
    }

    @PostMapping("/imports/preview")
    public ApiResponse<CommandHistoryImportService.ImportPreview> preview(@RequestParam("file") MultipartFile file,
                                                                          HttpServletRequest servletRequest) {
        return ApiResponse.ok(importService.preview(file, operatorContext.currentOperator(servletRequest)));
    }

    @PostMapping("/imports")
    public ApiResponse<CommandHistoryImportService.ImportResult> importArchive(
            @RequestBody CommandHistoryImportService.ImportConfirmRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(importService.importArchive(request, operatorContext.currentOperator(servletRequest)));
    }
}
