package dev.ricardo.notification.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        String id = req.getHeader("X-Request-Id");
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) id = UUID.randomUUID().toString();
        MDC.put("requestId", id); res.setHeader("X-Request-Id", id);
        try { chain.doFilter(req, res); }
        finally { MDC.clear(); if (previous != null) MDC.setContextMap(previous); }
    }
}
