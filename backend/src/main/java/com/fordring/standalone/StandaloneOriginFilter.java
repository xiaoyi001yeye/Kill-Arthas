package com.fordring.standalone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "fordring.standalone.enabled", havingValue = "true")
public class StandaloneOriginFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")
                && !StandaloneOrigin.matches(request.getHeader("Origin"), requestUri(request))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-origin requests are not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private URI requestUri(HttpServletRequest request) {
        var scheme = request.getScheme();
        var port = request.getServerPort();
        return URI.create(scheme + "://" + request.getServerName() + ":" + port + request.getRequestURI());
    }
}
