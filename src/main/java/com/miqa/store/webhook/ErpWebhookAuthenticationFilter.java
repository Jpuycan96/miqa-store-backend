package com.miqa.store.webhook;

import com.miqa.store.error.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Not a servlet bean: installed only in the webhook security chain. */
final class ErpWebhookAuthenticationFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 4096;
    static final String BODY = ErpWebhookAuthenticationFilter.class.getName() + ".body";
    static final String TIMESTAMP = "X-MIQA-Webhook-Timestamp";
    static final String SIGNATURE = "X-MIQA-Webhook-Signature";
    private final ErpWebhookProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    ErpWebhookAuthenticationFilter(ErpWebhookProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties; this.mapper = mapper; this.clock = clock;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        response.setHeader("Cache-Control", "no-store");
        try {
            if (!properties.receiveEnabled()) throw new ErpWebhookFailure(404, "NOT_FOUND");
            if (!request.getRequestURI().equals(request.getContextPath() + ErpWebhookController.PATH))
                throw new ErpWebhookFailure(404, "NOT_FOUND");
            if (!"POST".equals(request.getMethod())) throw new ErpWebhookFailure(405, "METHOD_NOT_ALLOWED");
            String timestamp = oneHeader(request, TIMESTAMP);
            String signature = oneHeader(request, SIGNATURE);
            if (timestamp == null || !timestamp.matches("[1-9][0-9]{0,11}")
                    || signature == null || !signature.matches("v1=[0-9a-f]{64}"))
                throw new ErpWebhookFailure(401, "UNAUTHENTICATED");
            long supplied = Long.parseLong(timestamp);
            long now = clock.instant().getEpochSecond();
            if (supplied < now - properties.timestampToleranceSeconds()
                    || supplied > now + properties.timestampToleranceSeconds())
                throw new ErpWebhookFailure(401, "UNAUTHENTICATED");
            if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new ErpWebhookFailure(413, "PAYLOAD_TOO_LARGE");
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw new ErpWebhookFailure(413, "PAYLOAD_TOO_LARGE");
            if (!MessageDigest.isEqual(sign(properties.secret(), timestamp, body),
                    HexFormat.of().parseHex(signature.substring(3))))
                throw new ErpWebhookFailure(401, "UNAUTHENTICATED");
            String contentType = request.getContentType();
            if (contentType == null || !contentType.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
                    || request.getHeader("Content-Encoding") != null || request.getQueryString() != null)
                throw new ErpWebhookFailure(400, "INVALID_EVENT");
            request.setAttribute(BODY, body);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("erp-catalog-webhook", null,
                    List.of(new SimpleGrantedAuthority(ErpWebhookSecurity.AUTHORITY))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } catch (ErpWebhookFailure failure) {
            reject(mapper, response, failure.status(), failure.code());
        } finally { SecurityContextHolder.clearContext(); }
    }
    private static String oneHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }
    static byte[] sign(byte[] secret, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "\n").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable"); }
    }
    static void reject(ObjectMapper mapper, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(new ApiError(Instant.now(), status, code,
                "Webhook no aceptado", ErpWebhookController.PATH, Map.of())));
    }
    @Override public String toString() { return "ErpWebhookAuthenticationFilter[REDACTED]"; }
}
