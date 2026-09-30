package com.miqa.store.erp;

import com.miqa.store.admin.*;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real existing security chain and MVC, with no Boot, DataSource or Flyway. */
class ErpCatalogSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class, ErpCatalogController.class})
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean ErpCatalogService service() { return mock(ErpCatalogService.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var props = new java.util.Properties();
            props.put("app.admin.jwt-secret", java.util.Base64.getEncoder().encodeToString(new byte[32]));
            config.setProperties(props);
            return config;
        }
    }
    @Test void allNewEndpointsRequireExistingAdminAuthentication() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(post("/api/admin/erp-catalog/sync")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/erp-catalog/sync")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/erp-catalog/services")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/erp-catalog/bindings/p")).andExpect(status().isUnauthorized());
            mvc.perform(put("/api/admin/erp-catalog/bindings/p").contentType("application/json")
                    .content("{\"erpServiceId\":\"17\",\"active\":true}")).andExpect(status().isUnauthorized());
            verifyNoInteractions(context.getBean(ErpCatalogService.class));
            when(context.getBean(AdminUserRepository.class).existsByIdAndActiveTrue("test-admin")).thenReturn(true);
            var now = java.time.Instant.now();
            var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                    .issuer("miqa-store-admin").subject("test-admin").audience(java.util.List.of("miqa-store-admin-api"))
                    .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
            var header = org.springframework.security.oauth2.jwt.JwsHeader
                    .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
            String token = context.getBean(org.springframework.security.oauth2.jwt.JwtEncoder.class)
                    .encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
            when(context.getBean(ErpCatalogService.class).synchronize())
                    .thenReturn(new ErpCatalogDtos.SyncStatus("SUCCESS", now, now, 0, 0, 0));
            mvc.perform(post("/api/admin/erp-catalog/sync").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SUCCESS"));
            verify(context.getBean(ErpCatalogService.class)).synchronize();
            mvc.perform(put("/api/admin/erp-catalog/bindings/p").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"erpServiceId\":\"name-not-id\",\"active\":true}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
