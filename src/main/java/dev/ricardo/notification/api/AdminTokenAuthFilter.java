package dev.ricardo.notification.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AdminTokenAuthFilter extends OncePerRequestFilter {
    private final String token;
    private final ObjectMapper mapper;
    public AdminTokenAuthFilter(String token, ObjectMapper mapper) { this.token=token==null?"":token.trim(); this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        String supplied=req.getHeader("X-Admin-Token");
        HttpStatus status=token.isEmpty()?HttpStatus.NOT_FOUND: supplied==null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)) ? HttpStatus.UNAUTHORIZED:null;
        if(status!=null) {
            res.setStatus(status.value()); res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(res.getWriter(),ProblemDetail.forStatusAndDetail(status,token.isEmpty()?"Resource not found":"Invalid or missing X-Admin-Token")); return;
        }
        res.setHeader("Cache-Control","no-store"); chain.doFilter(req,res);
    }
}
