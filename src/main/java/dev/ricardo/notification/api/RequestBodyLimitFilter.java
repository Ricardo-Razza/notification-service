package dev.ricardo.notification.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.*;

public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final int maxBytes;
    private final ObjectMapper mapper;
    public RequestBodyLimitFilter(int maxBytes, ObjectMapper mapper) { this.maxBytes = maxBytes; this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!req.getRequestURI().startsWith("/api/") || "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod())) {
            chain.doFilter(req, res); return;
        }
        if (req.getContentLengthLong() > maxBytes) { reject(res); return; }
        byte[] body = req.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) { reject(res); return; }
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override public ServletInputStream getInputStream() {
                var stream = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public int read(byte[] b, int off, int len) { return stream.read(b, off, len); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, res);
    }
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(413); res.setContentType("application/problem+json");
        mapper.writeValue(res.getWriter(), ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds configured limit"));
    }
}
