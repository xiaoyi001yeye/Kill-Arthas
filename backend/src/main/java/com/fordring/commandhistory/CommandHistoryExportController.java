package com.fordring.commandhistory;

import com.fordring.operator.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/command-history")
public class CommandHistoryExportController {
    private static final Logger log = LoggerFactory.getLogger(CommandHistoryExportController.class);
    private final CommandHistoryExportService exportService;
    private final OperatorContext operatorContext;

    public CommandHistoryExportController(CommandHistoryExportService exportService, OperatorContext operatorContext) {
        this.exportService = exportService;
        this.operatorContext = operatorContext;
    }

    @PostMapping("/exports")
    public ResponseEntity<byte[]> export(@RequestBody CommandHistoryExportService.ExportRequest request,
                                         HttpServletRequest servletRequest) {
        var operator = operatorContext.currentOperator(servletRequest);
        var count = request.historyIds() == null ? 0 : request.historyIds().size();
        log.info("Command history export HTTP request operator={} historyIdCount={}", operator, count);
        var archive = exportService.export(request, operator);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(archive.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(archive.content());
    }
}
