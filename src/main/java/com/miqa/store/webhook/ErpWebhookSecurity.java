package com.miqa.store.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;

@Configuration
public class ErpWebhookSecurity {
    static final String AUTHORITY = "erp_catalog:webhook";
    @Bean @Order(0) @ConditionalOnWebApplication
    SecurityFilterChain erpWebhookSecurityFilterChain(HttpSecurity http, ObjectMapper mapper, ErpWebhookProperties properties)
            throws Exception {
        // Higher priority and narrower matcher than the existing read-only /erp/** export chain.
        return http.securityMatcher(ErpWebhookController.PATH, ErpWebhookController.PATH + "/**")
                .csrf(c -> c.disable()).cors(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, ErpWebhookController.PATH)
                        .hasAuthority(AUTHORITY).anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> ErpWebhookAuthenticationFilter.reject(mapper,r,401,"UNAUTHENTICATED"))
                        .accessDeniedHandler((q,r,x) -> ErpWebhookAuthenticationFilter.reject(mapper,r,403,"FORBIDDEN")))
                .addFilterBefore(new ErpWebhookAuthenticationFilter(properties, mapper, Clock.systemUTC()), AnonymousAuthenticationFilter.class)
                .build();
    }
}
