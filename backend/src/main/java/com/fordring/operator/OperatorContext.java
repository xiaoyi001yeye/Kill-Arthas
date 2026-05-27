package com.fordring.operator;

import com.fordring.config.FordringProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class OperatorContext {
    private final FordringProperties properties;

    public OperatorContext(FordringProperties properties) {
        this.properties = properties;
    }

    public String currentOperator(HttpServletRequest request) {
        var header = request.getHeader("X-Fordring-Operator");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        return properties.operator.defaultName;
    }
}

