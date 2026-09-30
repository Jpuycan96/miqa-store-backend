package com.miqa.store.erp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ErpCatalogClientTest {
    @Test void sendsKeyOnlyAsBackendHeaderAndNeverFollowsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var header = new AtomicReference<String>();
        var requestUri = new AtomicReference<String>();
        var response = new AtomicReference<>("[" + ErpCatalogTest.JSON + "]");
        var code = new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext("/api/integracion/tienda-virtual/v1/servicios", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-ERP-Service-Key"));
            requestUri.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().add("Location", "/redirected");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var redirected = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/redirected", exchange -> { redirected.set(true); exchange.close(); });
        server.start();
        try {
            var client = new ErpCatalogClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-key");
            assertThat(client.fetchAvailable()).hasSize(1);
            assertThat(header).hasValue("synthetic-key");
            assertThat(requestUri.get()).doesNotContain("synthetic-key");
            for (int status : new int[]{302, 401, 500, 206}) {
                code.set(status);
                response.set("sensitive remote details synthetic-key");
                assertThatThrownBy(client::fetchAvailable).isInstanceOf(ErpCatalogFailure.class)
                        .hasMessage("ERP_ERROR").hasNoCause();
            }
            assertThat(redirected).isFalse();
            code.set(200);
            response.set("[");
            assertThatThrownBy(client::fetchAvailable).hasMessage("INVALID_CONTRACT").hasNoCause();
            response.set(" ".repeat(10 * 1024 * 1024 + 1));
            assertThatThrownBy(client::fetchAvailable).isInstanceOf(ErpCatalogFailure.class).hasNoCause();
        } finally { server.stop(0); }
    }
    @Test void absentConfigAndInsecureRemoteUrlFailWithoutNetwork() {
        assertThatThrownBy(() -> new ErpCatalogClient("", "").fetchAvailable()).hasMessage("NOT_CONFIGURED");
        assertThatThrownBy(() -> new ErpCatalogClient("not-a-url", "synthetic-key").fetchAvailable()).hasMessage("NOT_CONFIGURED");
        assertThatThrownBy(() -> new ErpCatalogClient("http://example.invalid", "synthetic-key").fetchAvailable())
                .hasMessage("NOT_CONFIGURED");
        assertThatThrownBy(() -> new ErpCatalogClient("https://user:password@example.invalid", "synthetic-key").fetchAvailable())
                .hasMessage("NOT_CONFIGURED");
    }
}
