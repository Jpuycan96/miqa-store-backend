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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public ErpCatalogClient(@Value("${app.erp.base-url:${ERP_TIENDA_VIRTUAL_BASE_URL:}}") String baseUrl,
                            @Value("${app.erp.api-key:${ERP_TIENDA_VIRTUAL_API_KEY:}}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
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
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                    .header("X-ERP-Service-Key", apiKey).GET().build();
            var response = http.send(request, info -> info.statusCode() == 200
                    ? new LimitedBody() : HttpResponse.BodySubscribers.replacing(""));
            if (response.statusCode() != 200) throw new ErpCatalogFailure("ERP_ERROR");
            return decode(response.body());
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
