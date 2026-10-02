package com.miqa.store.export;

import com.miqa.store.admin.*;
import com.miqa.store.error.ApiExceptionHandler;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestExportSecurityTest {
    private static final String KEY="synthetic-export-key-not-for-real-use-123456";
    private static final String PATH=RequestExportController.PATH;
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class,RequestExportSecurity.class,RequestExportController.class,ApiExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean RequestExportService service() { return mock(RequestExportService.class); }
    }
    private AnnotationConfigWebApplicationContext context(String key,String scopes) {
        var ctx=new AnnotationConfigWebApplicationContext(); ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of(
                "app.admin.jwt-secret",Base64.getEncoder().encodeToString(new byte[32]),
                "app.erp.requests.api-key",key,"app.erp.requests.scopes",scopes)));
        ctx.register(Config.class); ctx.refresh(); return ctx;
    }
    private MockMvc mvc(AnnotationConfigWebApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @Test void absentWrongDuplicateKeyAndAdminBearerCannotReadPersonalData() throws Exception {
        try(var ctx=context(KEY,RequestExportSecurity.READ)) {
            var mvc=mvc(ctx);
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(PATH).header("Authorization","Bearer admin-token")).andExpect(status().isUnauthorized());
            mvc.perform(get(PATH).header("X-ERP-Service-Key","outbound-erp-key")).andExpect(status().isUnauthorized());
            mvc.perform(get(PATH).header("X-ERP-Service-Key",KEY,KEY)).andExpect(status().isUnauthorized());
            mvc.perform(get(PATH+"/id").header("X-ERP-Service-Key","wrong")).andExpect(status().isUnauthorized());
            verifyNoInteractions(ctx.getBean(RequestExportService.class));
        }
    }
    @Test void validKeyWithoutSpecificPermissionIsForbiddenAndDisabledKeyFailsClosed() throws Exception {
        try(var ctx=context(KEY,"catalog:read solicitudes:write")) {
            mvc(ctx).perform(get(PATH).header("X-ERP-Service-Key",KEY)).andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control","no-store"));
            verifyNoInteractions(ctx.getBean(RequestExportService.class));
        }
        try(var ctx=context("",RequestExportSecurity.READ)) {
            mvc(ctx).perform(get(PATH).header("X-ERP-Service-Key",KEY)).andExpect(status().isUnauthorized());
            verifyNoInteractions(ctx.getBean(RequestExportService.class));
        }
    }
    @Test void permittedGetReturnsPageAndDetailButCannotWriteOrAccessAdmin() throws Exception {
        try(var ctx=context(KEY,RequestExportSecurity.READ)) {
            var mvc=mvc(ctx); var service=ctx.getBean(RequestExportService.class);
            var summary=RequestExportServiceTest.summary("id",RequestExportServiceTest.START);
            when(service.list(null,null,null,null)).thenReturn(new RequestExportDtos.Page(1,"MIQA_STORE",
                    new RequestExportDtos.Window(RequestExportServiceTest.START,RequestExportServiceTest.END),List.of(summary),null,false));
            when(service.detail("id")).thenReturn(new RequestExportDtos.Detail(1,"MIQA_STORE","id",summary.reference(),summary.origin(),summary.status(),
                    summary.createdAt(),summary.updatedAt(),new RequestExportDtos.Contact("Synthetic contact","999999999",null),null,List.of()));
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
            try {
                mvc.perform(get(PATH).header("X-ERP-Service-Key",KEY)).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.requests[0].id").value("id"))
                        .andExpect(jsonPath("$.requests[0].contact").doesNotExist());
                var body=mvc.perform(get(PATH+"/id").header("X-ERP-Service-Key",KEY)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.contact.name").value("Synthetic contact")).andExpect(header().string("Cache-Control","no-store"))
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain(KEY,"idempotencyKey","requestHash","request_hash");
                assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(KEY,"Synthetic contact","999999999"));
            } finally { logger.detachAppender(logs); logs.stop(); }
            for(var request:List.of(post(PATH),put(PATH+"/id"),patch(PATH+"/id"),delete(PATH+"/id"),head(PATH),options(PATH)))
                mvc.perform(request.header("X-ERP-Service-Key",KEY)).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/products").header("X-ERP-Service-Key",KEY)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/public/products")).andExpect(status().isNotFound()); // no controller in this test, public chain remains open
            mvc.perform(get("/api/integracion/erp/v1/unknown").header("X-ERP-Service-Key",KEY)).andExpect(status().isForbidden());
        }
    }
    @Test void missingRequestInvalidQueryAndHistoricalFailureAreSafeAndNoStore() throws Exception {
        try(var ctx=context(KEY,RequestExportSecurity.READ)) {
            var service=ctx.getBean(RequestExportService.class); var mvc=mvc(ctx);
            when(service.detail("missing")).thenThrow(new RequestExportFailure(404,"NOT_FOUND"));
            mvc.perform(get(PATH+"/missing").header("X-ERP-Service-Key",KEY)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(get(PATH).param("limit","bad").header("X-ERP-Service-Key",KEY)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control","no-store"));
            when(service.detail("broken")).thenThrow(new RequestExportFailure(409,"INVALID_HISTORICAL_SNAPSHOT"));
            mvc.perform(get(PATH+"/broken").header("X-ERP-Service-Key",KEY)).andExpect(status().isConflict());
        }
    }
}
