package com.miqa.store.quote;

import com.miqa.store.error.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** After Security CORS, before MVC/deserialization. Bounded even for chunked bodies. */
public class QuoteRequestBodyFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/public/quote-requests";
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private final ObjectMapper mapper;
    private final QuoteRequestRateLimit limit;
    public QuoteRequestBodyFilter(ObjectMapper mapper, QuoteRequestRateLimit limit) { this.mapper = mapper; this.limit = limit; }

    public static boolean matches(HttpServletRequest request) {
        return request.getRequestURI().equals(request.getContextPath() + PATH);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matches(request) || !"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        int retry = limit.retryAfterSeconds();
        if (retry > 0) {
            response.setHeader("Retry-After", Integer.toString(retry));
            reject(response, 429, "TOO_MANY_REQUESTS", "Demasiadas solicitudes; inténtalo más tarde");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) { tooLarge(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) { tooLarge(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        reject(response, 413, "PAYLOAD_TOO_LARGE", "La solicitud no debe superar 64 KiB");
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(new ApiError(Instant.now(), status, code, message, PATH, Map.of())));
    }
}
