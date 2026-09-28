package com.miqa.store.quote;

import com.miqa.store.config.CorsConfiguration;
import com.miqa.store.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.miqa.store.quote.QuoteRequestCanonicalizerTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure tests: no application context, server, DataSource or Flyway. */
class QuoteRequestHttpTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockHttpServletRequest request(byte[] content) {
        var request = new MockHttpServletRequest("POST", QuoteRequestBodyFilter.PATH);
        request.setContentType("application/json");
        request.setContent(content);
        return request;
    }
    @Test void controllerReturnsOnlyMinimalConfirmationWith201Or200() {
        var service = mock(QuoteRequestService.class);
        var input = submission(quantity(50));
        var confirmation = new QuoteRequestDtos.Confirmation("MIQA-000001", Instant.parse("2026-09-28T10:00:00Z"), "Solicitud recibida");
        for (boolean replay : new boolean[]{false, true}) {
            when(service.submit(KEY, input)).thenReturn(new QuoteRequestDtos.Result(confirmation, replay));
            var response = new QuoteRequestController(service).submit(KEY, input);
            assertThat(response.getStatusCode().value()).isEqualTo(replay ? 200 : 201);
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            var json = mapper.readTree(mapper.writeValueAsString(response.getBody()));
            assertThat(json.propertyNames()).containsExactlyInAnyOrder("reference", "receivedAt", "confirmation");
            assertThat(json.toString()).doesNotContain(KEY, "Cliente", "999999999", "hash", "snapshot", "email");
        }
    }
    @Test void bodyWithinLimitIsAvailableToMvcAndNoStoreIsSet() throws Exception {
        byte[] content = "{\"contact\":\"José\"}".getBytes(StandardCharsets.UTF_8);
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        new QuoteRequestBodyFilter(mapper, new QuoteRequestRateLimit(10)).doFilter(request(content), response, (req, res) -> {
            assertThat(req.getInputStream().readAllBytes()).isEqualTo(content);
            reached.set(true);
        });
        assertThat(reached).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void exactly64KiBIsAllowedAndOneAdditionalByteIsRejected() throws Exception {
        var filter = new QuoteRequestBodyFilter(mapper, new QuoteRequestRateLimit(10));
        var reached = new AtomicInteger();
        var allowed = new MockHttpServletResponse();
        filter.doFilter(request(new byte[65536]), allowed, (req, res) -> reached.incrementAndGet());
        var denied = new MockHttpServletResponse();
        filter.doFilter(request(new byte[65537]), denied, (req, res) -> reached.incrementAndGet());
        assertThat(reached).hasValue(1);
        assertThat(denied.getStatus()).isEqualTo(413);
        assertThat(denied.getContentAsString()).contains("64 KiB").doesNotContain("5 MB");
        assertThat(denied.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void chunkedBodyCannotBypass64KiBLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", QuoteRequestBodyFilter.PATH) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContent(new byte[65537]);
        var response = new MockHttpServletResponse();
        new QuoteRequestBodyFilter(mapper, new QuoteRequestRateLimit(10)).doFilter(request, response, (req, res) -> fail("Oversized body reached MVC"));
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void globalRateBudgetReturns429AndDoesNotTrustForwardedIp() throws Exception {
        var filter = new QuoteRequestBodyFilter(mapper, new QuoteRequestRateLimit(1));
        filter.doFilter(request(new byte[0]), new MockHttpServletResponse(), (req, res) -> {});
        var next = request(new byte[0]);
        next.addHeader("X-Forwarded-For", "203.0.113.99");
        next.addHeader("Idempotency-Key", KEY);
        var response = new MockHttpServletResponse();
        filter.doFilter(next, response, (req, res) -> fail("Rate budget exceeded"));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain(KEY, "203.0.113.99");
    }
    @Test void rateBudgetRecoversAfterWindowAndIsAtomicAcrossThreads() throws Exception {
        var clock = new AtomicLong();
        var limit = new QuoteRequestRateLimit(5, clock::get);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < 30; i++) futures.add(executor.submit(limit::retryAfterSeconds));
            int accepted = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS) == 0) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        clock.set(60_000_000_000L);
        assertThat(limit.retryAfterSeconds()).isZero();
    }
    @Test void catalogAndAdminRequestsAreUnaffectedByBodyFilter() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/admin/products/x/images");
        request.setContent(new byte[65537]);
        var reached = new AtomicBoolean();
        new QuoteRequestBodyFilter(mapper, new QuoteRequestRateLimit(1)).doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
    }
    @Test void corsAllowsOnlyConfiguredOriginsAndNecessaryPostHeaders() throws Exception {
        var source = cors();
        var request = preflight("http://localhost:4200", QuoteRequestBodyFilter.PATH, "POST", "content-type,idempotency-key");
        var response = new MockHttpServletResponse();
        assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4200");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST").doesNotContain("DELETE");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("idempotency-key");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        for (var denied : new MockHttpServletRequest[]{
                preflight("https://untrusted.example", QuoteRequestBodyFilter.PATH, "POST", "idempotency-key"),
                preflight("http://localhost:4200", QuoteRequestBodyFilter.PATH, "DELETE", "idempotency-key"),
                preflight("http://localhost:4200", "/api/public/products", "POST", "idempotency-key"),
                preflight("http://localhost:4200", QuoteRequestBodyFilter.PATH, "POST", "authorization")}) {
            var deniedResponse = new MockHttpServletResponse();
            assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(denied), denied, deniedResponse)).isFalse();
            assertThat(deniedResponse.getStatus()).isEqualTo(403);
        }
    }
    @Test void conflictErrorsAreSpecificAndNeverEchoContactOrSql() {
        var errors = new ApiExceptionHandler();
        var exception = new org.springframework.dao.DataIntegrityViolationException("private@example.test SQL details");
        var quote = errors.conflict(exception, request(new byte[0]));
        assertThat(quote.getBody().message()).contains("solicitud").doesNotContain("slug", "private", "SQL");
        var admin = errors.conflict(exception, new MockHttpServletRequest("POST", "/api/admin/products"));
        assertThat(admin.getBody().message()).contains("slug o nombre");
    }
    private UrlBasedCorsConfigurationSource cors() {
        class Registry extends CorsRegistry {
            UrlBasedCorsConfigurationSource source() {
                var source = new UrlBasedCorsConfigurationSource();
                getCorsConfigurations().forEach(source::registerCorsConfiguration);
                return source;
            }
        }
        var registry = new Registry();
        new CorsConfiguration("http://localhost:4200").addCorsMappings(registry);
        return registry.source();
    }
    private MockHttpServletRequest preflight(String origin, String path, String method, String headers) {
        var request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", headers);
        return request;
    }
}
