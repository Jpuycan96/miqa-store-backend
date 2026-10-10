package com.miqa.store.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Component
public class ErpCatalogClient {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final Duration catalogDeadline;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @org.springframework.beans.factory.annotation.Autowired
    public ErpCatalogClient(@Value("${app.erp.base-url:${ERP_TIENDA_VIRTUAL_BASE_URL:}}") String baseUrl,
                            @Value("${app.erp.api-key:${ERP_TIENDA_VIRTUAL_API_KEY:}}") String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(20));
    }

    ErpCatalogClient(String baseUrl, String apiKey, HttpClient http, Duration catalogDeadline) {
        if (catalogDeadline.isNegative() || catalogDeadline.isZero()) throw new IllegalArgumentException("Invalid deadline");
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.http = http;
        this.catalogDeadline = catalogDeadline;
    }

    public List<ErpCatalogContract> fetchAvailable() {
        if (baseUrl.isBlank() || apiKey.isBlank()) throw new ErpCatalogFailure("NOT_CONFIGURED");
        try {
            URI base = URI.create(baseUrl);
            boolean loopback = base.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost());
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !("https".equals(base.getScheme())
                    || ("http".equals(base.getScheme()) && loopback))) {
                throw new ErpCatalogFailure("NOT_CONFIGURED");
            }
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "")
                    + "/api/integracion/tienda-virtual/v1/servicios"))
                    .timeout(catalogDeadline).header("Accept", "application/json")
                    .header("X-ERP-Service-Key", apiKey).GET().build();
            // sendAsync completes only after the subscriber has consumed the entire body.
            // The independent monotonic deadline also covers stalled/error response bodies.
            long deadline = System.nanoTime() + catalogDeadline.toNanos();
            var pending = http.sendAsync(request, info -> info.statusCode() == 200
                    ? new LimitedBody() : HttpResponse.BodySubscribers.replacing(""));
            try {
                var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (response.statusCode() != 200) throw new ErpCatalogFailure("ERP_ERROR");
                return decode(response.body());
            } finally {
                // Cancels the HTTP exchange on timeout/interruption, releasing transport resources.
                pending.cancel(true);
            }
        } catch (ErpCatalogFailure ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ErpCatalogFailure("ERP_ERROR");
        } catch (Exception ex) {
            throw new ErpCatalogFailure("ERP_ERROR");
        }
    }

    List<ErpCatalogContract> decode(String body) {
        try {
            var result = mapper.readValue(body, ErpCatalogContract[].class);
            if (result == null) throw new IllegalArgumentException();
            return List.of(result);
        } catch (Exception ex) {
            throw new ErpCatalogFailure("INVALID_CONTRACT");
        }
    }

    /** Fixed read-only route; callers cannot supply URLs or headers. */
    public PricingReply evaluatePrice(Object input) {
        try {
            URI base = URI.create(baseUrl);
            boolean loopback = List.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost() == null ? "" : base.getHost());
            if (apiKey.isBlank() || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && loopback))
                throw new ErpCatalogFailure("NOT_CONFIGURED");
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "")
                    + "/api/integracion/tienda-virtual/v1/precios/evaluar"))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                    .header("Content-Type", "application/json").header("X-ERP-Service-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build();
            var pending = http.sendAsync(request, info -> List.of(200,409,422).contains(info.statusCode())
                    ? new LimitedBody() : HttpResponse.BodySubscribers.replacing(""));
            try {
                var response = pending.get(20, TimeUnit.SECONDS);
                if (response.body().contains(apiKey)) throw new ErpCatalogFailure("INVALID_CONTRACT");
                return new PricingReply(response.statusCode(), response.body());
            } finally { pending.cancel(true); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ErpCatalogFailure("ERP_ERROR");
        } catch (Exception ex) { throw new ErpCatalogFailure("ERP_ERROR"); }
    }
    public record PricingReply(int status, String body) {
        @Override public String toString() { return "PricingReply[REDACTED]"; }
    }

    @Override public String toString() { return "ErpCatalogClient[REDACTED]"; }

    /** Bound allocations before parsing; a rejected/partial body can never mean an empty catalog. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private static final int MAX_BYTES = 10 * 1024 * 1024;
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        @Override public CompletionStage<String> getBody() {
            return delegate.getBody().thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8));
        }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) received += buffer.remaining();
            if (received > MAX_BYTES) {
                subscription.cancel();
                delegate.onError(new ErpCatalogFailure("INVALID_CONTRACT"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
