package com.miqa.store.erp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErpCatalogDeadlineTest {
    @ParameterizedTest @CsvSource({"200,false","200,true","503,false","503,true"})
    void headersAndPartialBodyCannotHoldRequestAndCancellationClosesTransport(int status, boolean partial) throws Exception {
        // Raw synthetic HTTP peer verifies cancellation on the wire, including discarded error bodies.
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var http = HttpClient.newHttpClient()) {
            server.setSoTimeout(3000);
            var headers = new CountDownLatch(1);
            var disconnected = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    while (!reader.readLine().isEmpty()) { /* Consume only synthetic request headers. */ }
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\nContent-Length: 2\r\n\r\n" + (partial ? "[" : ""))
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); headers.countDown();
                    return socket.getInputStream().read() == -1;
                } catch (java.net.SocketException closed) { return true; }
            });
            var client = new ErpCatalogClient("http://127.0.0.1:" + server.getLocalPort(), "synthetic-key", http,
                    Duration.ofMillis(700));
            long start = System.nanoTime();
            assertThatThrownBy(client::fetchAvailable).isInstanceOf(ErpCatalogFailure.class).hasMessage("ERP_ERROR").hasNoCause();
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
            assertThat(disconnected.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void tricklingBodyStillHitsTotalDeadlineAndNextRequestCanSucceed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var slow = new AtomicBoolean(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var http = HttpClient.newHttpClient()) {
            server.setExecutor(executor);
            server.createContext("/api/integracion/tienda-virtual/v1/servicios", exchange -> {
                byte[] body = ("[" + ErpCatalogTest.JSON + "]").getBytes(StandardCharsets.UTF_8);
                boolean trickle = slow.getAndSet(false);
                exchange.sendResponseHeaders(200, body.length);
                try {
                    if (trickle) for (byte b : body) {
                        exchange.getResponseBody().write(b); exchange.getResponseBody().flush();
                        Thread.sleep(30);
                    }
                    else exchange.getResponseBody().write(body);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException cancelled) { /* Expected disconnect on the stalled first request. */ }
                finally { exchange.close(); }
            });
            server.start();
            try {
                var client = new ErpCatalogClient("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-key", http,
                        Duration.ofMillis(700));
                long start = System.nanoTime();
                assertThatThrownBy(client::fetchAvailable).hasMessage("ERP_ERROR").hasNoCause();
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
                assertThat(client.fetchAvailable()).hasSize(1);
            } finally { server.stop(0); }
        }
    }

    @Test void interruptionCancelsPendingExchangeAndPreservesInterruptFlag() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> { sent.countDown(); return pending; });
        var client = new ErpCatalogClient("http://127.0.0.1:1", "synthetic-key", http, Duration.ofSeconds(10));
        var preserved = new AtomicBoolean();
        var assertion = new CompletableFuture<Void>();
        var reader = new Thread(() -> {
            try {
                assertThatThrownBy(client::fetchAvailable).hasMessage("ERP_ERROR").hasNoCause();
                preserved.set(Thread.currentThread().isInterrupted()); assertion.complete(null);
            } catch (Throwable failure) { assertion.completeExceptionally(failure); }
        });
        reader.start();
        try {
            assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue(); reader.interrupt();
            assertion.get(3, TimeUnit.SECONDS);
            assertThat(pending.isCancelled()).isTrue(); assertThat(preserved).isTrue();
        } finally { reader.interrupt(); reader.join(3000); }
    }

    @Test void deadlineFlowsThroughUnchangedSynchronizerToWorkerRetryWithoutReconcilingPartialCatalog() {
        var http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<String>> stalled = new CompletableFuture<>();
        HttpResponse<String> recovered = mock(HttpResponse.class);
        when(recovered.statusCode()).thenReturn(200); when(recovered.body()).thenReturn("[]");
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stalled, CompletableFuture.completedFuture(recovered));
        var client = new ErpCatalogClient("http://127.0.0.1:1", "synthetic-key", http, Duration.ofMillis(100));
        var repository = mock(ErpCatalogRepository.class);
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(i -> new org.springframework.transaction.support.SimpleTransactionStatus(true));
        when(repository.trySyncLock()).thenReturn(true);
        var now = java.time.Instant.now();
        when(repository.status()).thenReturn(new ErpCatalogDtos.SyncStatus("ERP_ERROR",now,null,0,0,0),
                new ErpCatalogDtos.SyncStatus("SUCCESS",now,now,0,0,0));
        when(repository.revisions()).thenReturn(java.util.Map.of());
        when(repository.configurationVersions()).thenReturn(java.util.Map.of());
        var service = new ErpCatalogService(client,repository,transactions);
        var queue = mock(com.miqa.store.webhook.ErpWebhookQueue.class);
        var released = new AtomicBoolean();
        when(queue.withWorkerLock(any())).thenAnswer(i -> {
            released.set(false);
            try { ((Runnable)i.getArgument(0)).run(); return true; } finally { released.set(true); }
        });
        var batch = new com.miqa.store.webhook.ErpWebhookQueue.Batch(java.util.UUID.randomUUID(),1,1);
        when(queue.claim()).thenReturn(java.util.Optional.of(batch));
        var properties = new com.miqa.store.webhook.ErpWebhookProperties(false,true,"",300,5000,3,200,30,900,6);
        var worker = new com.miqa.store.webhook.ErpWebhookWorker(properties,queue,service);
        worker.tick();
        assertThat(stalled.isCancelled()).isTrue(); assertThat(released).isTrue();
        verify(queue).retry(batch,"ERP_ERROR",30);
        verify(repository).failure(any(),eq("ERP_ERROR"));
        verify(repository,never()).missing(anyString(),any()); verify(repository,never()).success(any(),anyInt(),anyInt(),anyInt());
        verify(transactions).commit(any());
        worker.tick(); // Queue cooldown selection is covered separately; this represents its next due claim.
        verify(queue).complete(batch); verify(repository).success(any(),eq(0),eq(0),eq(0));
        assertThat(released).isTrue();
    }
}
