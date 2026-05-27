package com.fordring.command;

import com.fordring.common.enums.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RiskService {
    public RiskCheckResult check(String command) {
        var normalized = command == null ? "" : command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return new RiskCheckResult(RiskLevel.DENY, "EMPTY_COMMAND", "命令不能为空", false);
        }
        var first = normalized.split(" ")[0];
        if (first.equals("shutdown") || first.equals("stop")) {
            return new RiskCheckResult(RiskLevel.DENY, "DANGEROUS_DENY", "该命令在 MVP 中默认禁止执行", false);
        }
        if (first.equals("reset") || first.equals("heapdump") || first.equals("watch")
                || first.equals("trace") || first.equals("monitor")) {
            return new RiskCheckResult(RiskLevel.CONFIRM, first.toUpperCase(Locale.ROOT) + "_CONFIRM",
                    first + " 命令可能持续输出或影响目标性能，请确认后执行", true);
        }
        return new RiskCheckResult(RiskLevel.ALLOW, "DEFAULT_ALLOW", "允许执行", true);
    }

    public record RiskCheckResult(RiskLevel riskLevel, String matchedRule, String message, boolean executable) {
    }
}

