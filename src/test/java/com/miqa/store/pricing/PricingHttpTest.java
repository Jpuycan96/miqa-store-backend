package com.miqa.store.pricing;

import com.miqa.store.admin.*;
import com.miqa.store.config.CorsConfiguration;
import com.miqa.store.error.ApiExceptionHandler;
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

class PricingHttpTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class,PricingController.class,ApiExceptionHandler.class,CorsConfiguration.class})
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean PricingService service() { return mock(PricingService.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config=new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var props=new java.util.Properties();
            props.put("app.admin.jwt-secret",java.util.Base64.getEncoder().encodeToString(new byte[32]));
            props.put("miqa.cors.allowed-origins","http://localhost:4200"); config.setProperties(props); return config;
        }
    }
    @Test void anonymousPostCorsNoStoreStrictFieldsAndSafeStatusCodes() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
            var service=context.getBean(PricingService.class);
            mvc.perform(options(PricingController.PATH).header("Origin","http://localhost:4200").header("Access-Control-Request-Method","POST")
                    .header("Access-Control-Request-Headers","Content-Type")).andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:4200"));
            mvc.perform(options(PricingController.PATH).header("Origin","https://foreign.invalid").header("Access-Control-Request-Method","POST"))
                    .andExpect(status().isForbidden());
            for(var state:PricingDtos.Status.values()) {
                when(service.evaluate(any())).thenReturn(PricingDtos.Historical.state(state).publicResult());
                mvc.perform(post(PricingController.PATH).contentType("application/json")
                        .content("{\"productId\":\"banner\",\"quantity\":1,\"erpMaterialId\":\"10\",\"measures\":{}}"))
                        .andExpect(status().is(state.httpStatus())).andExpect(jsonPath("$.status").value(state.name()))
                        .andExpect(jsonPath("$.pricingRevision").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
            }
            clearInvocations(service);
            mvc.perform(post(PricingController.PATH).contentType("application/json").content("{\"erpServiceId\":\"1\",\"amount\":1}"))
                    .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("CONFIGURATION_INVALID"));
            mvc.perform(post(PricingController.PATH).contentType("application/json").content(" ".repeat(65537)))
                    .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","no-store"));
            verifyNoInteractions(service);
        }
    }
}
