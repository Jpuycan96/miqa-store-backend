package com.miqa.store.webhook;

import com.miqa.store.admin.*;
import com.miqa.store.error.ApiExceptionHandler;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Existing JWT decoder/filter and real MVC validation; no Boot, Flyway or database. */
class ErpWebhookRecoverySecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class,ErpWebhookAdminController.class,ApiExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean ErpWebhookQueue queue() { return mock(ErpWebhookQueue.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var props = new Properties(); props.put("app.admin.jwt-secret", Base64.getEncoder().encodeToString(new byte[32]));
            config.setProperties(props); return config;
        }
    }
    @Test void onlyActiveAdminCanListOrRequeueAndActorComesFromValidatedJwt() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
            String path = "/api/admin/erp-catalog/webhook-events";
            UUID event = UUID.randomUUID(), request = UUID.randomUUID();
            String body = "{\"requestId\":\"" + request + "\",\"reason\":\"Configuracion corregida\"}";
            var queue = context.getBean(ErpWebhookQueue.class);
            mvc.perform(get(path + "/failed")).andExpect(status().isUnauthorized());
            mvc.perform(post(path + "/" + event + "/requeue").contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path + "/" + event + "/requeue").header("X-ERP-Service-Key","synthetic-key")
                    .header("X-ERP-Signature","v1=synthetic").contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(path + "/failed").header("Authorization","Bearer invalid"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(queue);
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer("miqa-store-admin").subject("active-admin")
                    .audience(List.of("miqa-store-admin-api")).issuedAt(now).expiresAt(now.plusSeconds(60)).build();
            String token = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
            // Even a correctly signed token cannot authorize a disabled account.
            mvc.perform(get(path + "/failed").header("Authorization","Bearer " + token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(queue);
            when(context.getBean(AdminUserRepository.class).existsByIdAndActiveTrue("active-admin")).thenReturn(true);
            when(queue.failed(50)).thenReturn(List.of(new ErpWebhookQueue.FailedEvent(event,now,4,"INVALID_CONTRACT")));
            when(queue.requeue(event,request,"active-admin","Configuracion corregida"))
                    .thenReturn(new ErpWebhookQueue.RequeueReceipt(request,event,now,now.plusSeconds(3),"REQUEUED"));
            mvc.perform(get(path + "/failed").header("Authorization","Bearer " + token))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$[0].attempts").value(4));
            mvc.perform(post(path + "/" + event + "/requeue").header("Authorization","Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isAccepted())
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value("REQUEUED"));
            verify(queue).requeue(event,request,"active-admin","Configuracion corregida");
            for (String invalid : List.of("{}", "{\"requestId\":\"invalid\",\"reason\":\"Configuracion corregida\"}",
                    "{\"requestId\":\"" + request + "\",\"reason\":\"corto\"}"))
                mvc.perform(post(path + "/" + event + "/requeue").header("Authorization","Bearer " + token)
                        .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            when(queue.requeue(event,request,"active-admin","Configuracion corregida"))
                    .thenThrow(new AdminFailure(409,"Solo se pueden recuperar eventos FAILED"));
            mvc.perform(post(path + "/" + event + "/requeue").header("Authorization","Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isConflict());
            verify(queue,times(2)).requeue(any(),any(),any(),any());
        }
    }
}
