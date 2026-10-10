package com.miqa.store.webhook;

import com.miqa.store.admin.*;
import com.miqa.store.error.ApiExceptionHandler;
import com.miqa.store.erp.ErpCatalogService;
import com.miqa.store.export.RequestExportSecurity;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security chains and MVC with component scanning; no Boot, DB or Flyway. */
class ErpWebhookSecurityTest {
    private static final String EXPORT_KEY = "synthetic-export-key-00000000000000000000";
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class, RequestExportSecurity.class, ApiExceptionHandler.class})
    @ComponentScan(basePackageClasses=ErpWebhookSecurity.class, useDefaultFilters=false,
            includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,
                    classes={ErpWebhookSecurity.class, ErpWebhookProperties.class, ErpWebhookController.class,
                            ErpWebhookReceiver.class, ErpWebhookAdvice.class}))
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean ErpWebhookQueue queue() { return mock(ErpWebhookQueue.class); }
        @Bean ErpCatalogService synchronizer() { return mock(ErpCatalogService.class); }
    }
    private AnnotationConfigWebApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
                "app.admin.jwt-secret", Base64.getEncoder().encodeToString(new byte[32]),
                "app.erp.requests.api-key", EXPORT_KEY, "app.erp.requests.scopes", "solicitudes:read",
                "app.erp.webhook.receive-enabled", String.valueOf(enabled),
                "app.erp.webhook.secret", enabled ? SECRET : "")));
        context.register(Config.class); context.refresh(); return context;
    }
    private MockMvc mvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    private MockHttpServletRequestBuilder signed(String body, long timestamp) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post(ErpWebhookController.PATH).contentType("application/json").content(bytes)
                .header(ErpWebhookAuthenticationFilter.TIMESTAMP, Long.toString(timestamp))
                .header(ErpWebhookAuthenticationFilter.SIGNATURE, signature(Long.toString(timestamp), bytes));
    }
    private MockHttpServletRequestBuilder signed(String body) throws Exception {
        return signed(body, Instant.now().getEpochSecond());
    }
    @Test void validSignatureAcknowledgesDurableReceiptAndReplayWithoutCallingErp() throws Exception {
        try (var ctx = context(true)) {
            var queue = ctx.getBean(ErpWebhookQueue.class);
            when(queue.accept(any())).thenReturn(true, false);
            var mvc = mvc(ctx);
            mvc.perform(signed(BODY)).andExpect(status().isAccepted())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.eventId").value(ID))
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
            // Fresh transport signature can carry an old occurredAt and the same immutable event.
            mvc.perform(signed(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"));
            verify(queue, times(2)).accept(argThat(event -> event.eventId().toString().equals(ID)));
            verifyNoInteractions(ctx.getBean(ErpCatalogService.class));
        }
    }
    @Test void absentWrongChangedAndDuplicateAuthenticationCannotUseOtherCredentials() throws Exception {
        try (var ctx = context(true)) {
            var mvc = mvc(ctx);
            for (var request : List.of(post(ErpWebhookController.PATH),
                    post(ErpWebhookController.PATH).header("Authorization", "Bearer admin-token"),
                    post(ErpWebhookController.PATH).header("X-ERP-Service-Key", EXPORT_KEY),
                    signed(BODY).header(ErpWebhookAuthenticationFilter.SIGNATURE, "v1=" + "0".repeat(64)),
                    signed(BODY).header(ErpWebhookAuthenticationFilter.TIMESTAMP, "1"),
                    signed(BODY).content(BODY + " "))) {
                mvc.perform(request).andExpect(status().isUnauthorized())
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            mvc.perform(post(ErpWebhookController.PATH).contentType("application/json").content(BODY)
                    .header(ErpWebhookAuthenticationFilter.TIMESTAMP, timestamp)
                    .header(ErpWebhookAuthenticationFilter.SIGNATURE, "v1=" + "0".repeat(64)))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class));
        }
    }
    @Test void expiredAndFutureTimestampsAreRejectedEvenWithCorrectHmac() throws Exception {
        try (var ctx = context(true)) {
            long now = Instant.now().getEpochSecond();
            for (long timestamp : new long[]{now - 600, now + 600})
                mvc(ctx).perform(signed(BODY, timestamp)).andExpect(status().isUnauthorized());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class));
        }
    }
    @Test void signedMalformedUnknownAndNonUtf8PayloadsNeverReachPersistence() throws Exception {
        try (var ctx = context(true)) {
            var mvc = mvc(ctx);
            for (String body : List.of("", "[]", "{", BODY + " {}",
                    BODY.replace("ERP_CATALOG_CHANGED", "DELETE_PRODUCTS"), BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                    BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"), BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                    BODY.replace(ID, "1-1-1-1-1"), BODY.replace("10:00:00Z", "10:00:00+00:00"),
                    BODY.replace("2026-09-01", "9999-01-01"), BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"price\":100"),
                    BODY.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")))
                mvc.perform(signed(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EVENT"));
            byte[] utf16 = BODY.getBytes(StandardCharsets.UTF_16);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            mvc.perform(post(ErpWebhookController.PATH).contentType("application/json").content(utf16)
                    .header(ErpWebhookAuthenticationFilter.TIMESTAMP, timestamp)
                    .header(ErpWebhookAuthenticationFilter.SIGNATURE, signature(timestamp, utf16))).andExpect(status().isBadRequest());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class));
        }
    }
    @Test void sizeLimitAlsoAppliesWithoutContentLengthAndEncodingOrQueryAreRefused() throws Exception {
        try (var ctx = context(true)) {
            var mvc = mvc(ctx);
            mvc.perform(signed(" ".repeat(4097))).andExpect(status().isPayloadTooLarge());
            mvc.perform(signed(" ".repeat(4097)).with(request -> { request.addHeader("Content-Length", "-1"); return request; }))
                    .andExpect(status().isPayloadTooLarge());
            mvc.perform(signed(BODY).header("Content-Encoding", "gzip")).andExpect(status().isBadRequest());
            mvc.perform(signed(BODY).queryParam("action", "anything")).andExpect(status().isBadRequest());
            mvc.perform(signed(BODY).contentType("text/plain")).andExpect(status().isBadRequest());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class));
        }
    }
    @Test void disabledEmptySecretReturns404AndMakesNoQueueOrErpCalls() throws Exception {
        try (var ctx = context(false)) {
            mvc(ctx).perform(signed(BODY)).andExpect(status().isNotFound());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class), ctx.getBean(ErpCatalogService.class));
        }
    }
    @Test void databaseAndCommitFailureNeverAcknowledgeAndIdCollisionReturns409Safely() throws Exception {
        try (var ctx = context(true)) {
            var queue = ctx.getBean(ErpWebhookQueue.class); var mvc = mvc(ctx);
            for (RuntimeException failure : List.of(new DataAccessResourceFailureException(SECRET + " sensitive payload"),
                    new TransactionSystemException(SECRET + " sensitive payload"))) {
                doThrow(failure).when(queue).accept(any());
                String response = mvc.perform(signed(BODY)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("RECEIPT_UNAVAILABLE"))
                        .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
                assertThat(response).doesNotContain(SECRET, "sensitive payload", ID);
            }
            doThrow(new ErpWebhookFailure(409, "EVENT_ID_CONFLICT")).when(queue).accept(any());
            mvc.perform(signed(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"));
        }
    }
    @Test void webhookChainDoesNotGrantAdminOrExportAndExistingExportChainStillWorks() throws Exception {
        try (var ctx = context(true)) {
            var mvc = mvc(ctx);
            mvc.perform(get("/api/admin/products").header(ErpWebhookAuthenticationFilter.SIGNATURE, "v1=" + "0".repeat(64)))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/integracion/erp/v1/solicitudes").header("X-ERP-Service-Key", SECRET))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/integracion/erp/v1/solicitudes").header("X-ERP-Service-Key", EXPORT_KEY))
                    .andExpect(status().isNotFound()); // authenticated existing chain; no export controller in this context
            mvc.perform(get(ErpWebhookController.PATH)).andExpect(status().isMethodNotAllowed());
            mvc.perform(post(ErpWebhookController.PATH + "/other")).andExpect(status().isNotFound());
            mvc.perform(get("/api/public/products")).andExpect(status().isNotFound());
        }
    }
    @Test void evenValidAdministrativeJwtCannotAuthenticateWebhook() throws Exception {
        try (var ctx = context(true)) {
            when(ctx.getBean(AdminUserRepository.class).existsByIdAndActiveTrue("synthetic-admin")).thenReturn(true);
            var now = Instant.now();
            var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                    .issuer("miqa-store-admin").subject("synthetic-admin").audience(List.of("miqa-store-admin-api"))
                    .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
            var header = org.springframework.security.oauth2.jwt.JwsHeader
                    .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
            String jwt = ctx.getBean(org.springframework.security.oauth2.jwt.JwtEncoder.class)
                    .encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
            var mvc = mvc(ctx);
            mvc.perform(get("/api/admin/products").header("Authorization", "Bearer " + jwt)).andExpect(status().isNotFound());
            mvc.perform(post(ErpWebhookController.PATH).contentType("application/json").content(BODY)
                    .header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
            verifyNoInteractions(ctx.getBean(ErpWebhookQueue.class));
        }
    }
}
