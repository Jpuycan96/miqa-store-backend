package com.miqa.store.pricing;

import com.miqa.store.erp.ErpCatalogClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class PricingTransportTest {
    @Test void realHttpUsesFixedRouteHeaderAndSanitizesErrorsAndEchoedSecrets() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var code=new AtomicInteger(200); var body=new AtomicReference<>(ErpPricingTest.BODY);
        var key=new AtomicReference<String>(); var payload=new AtomicReference<String>();
        var method=new AtomicReference<String>(); var redirected=new AtomicBoolean();
        server.createContext("/api/integracion/tienda-virtual/v1/precios/evaluar", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-ERP-Service-Key"));
            method.set(exchange.getRequestMethod());
            payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location","/redirected");
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(),bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/redirected",exchange -> { redirected.set(true); exchange.close(); });
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        server.start();
        try {
            var client=new ErpCatalogClient("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-pricing-key");
            var pricing=new ErpPricing(client);
            assertThat(pricing.evaluate(ErpPricingTest.snapshot()).amount()).isEqualTo("73.25");
            assertThat(key.get()).isEqualTo("synthetic-pricing-key"); assertThat(method.get()).isEqualTo("POST");
            assertThat(payload.get()).contains("\"cantidad\":\"1\"","\"catalogRevision\"").doesNotContain("synthetic-pricing-key","amount","pricingRevision");
            for(int status:new int[]{401,403,500,503,302}) {
                code.set(status); body.set("synthetic-pricing-key internal diagnostics");
                var result=pricing.evaluate(ErpPricingTest.snapshot());
                assertThat(result.status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
                assertThat(result.toString()).doesNotContain("synthetic-pricing-key","diagnostics");
            }
            assertThat(redirected).isFalse();
            code.set(200); body.set(ErpPricingTest.BODY.replace("price-rev","synthetic-pricing-key"));
            assertThat(pricing.evaluate(ErpPricingTest.snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
            assertThat(client.toString()).doesNotContain("synthetic-pricing-key");
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("synthetic-pricing-key"));
        } finally { server.stop(0); logger.detachAppender(logs); logs.stop(); }
    }
    @Test void actualTimeoutIsTemporaryAndNeverQuoteRequired() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/api/integracion/tienda-virtual/v1/precios/evaluar",exchange -> {
            try { Thread.sleep(25000); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var pricing=new ErpPricing(new ErpCatalogClient("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-key"));
            assertThat(pricing.evaluate(ErpPricingTest.snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
