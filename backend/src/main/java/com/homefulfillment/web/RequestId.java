package com.homefulfillment.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestId extends OncePerRequestFilter {
    private static final String HEADER = "X-Request-Id";

    public static String value(HttpServletRequest request) {
        return (String) request.getAttribute(HEADER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}") ? supplied : UUID.randomUUID().toString();
        request.setAttribute(HEADER, id);
        response.setHeader(HEADER, id);
        try (var ignored = MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        }
    }
}
