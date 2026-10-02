package com.miqa.store.export;

import com.miqa.store.error.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Separate inbound identity. Never reuses the outbound ERP key or admin JWT permissions. */
@Configuration
public class RequestExportSecurity {
    public static final String READ="solicitudes:read";
    @Bean @Order(1) @ConditionalOnWebApplication
    SecurityFilterChain requestExportSecurityFilterChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${app.erp.requests.api-key:${MIQA_ERP_REQUESTS_API_KEY:}}") String key,
            @Value("${app.erp.requests.scopes:${MIQA_ERP_REQUESTS_SCOPES:}}") String scopes) throws Exception {
        var filter=new ServiceKeyFilter(key,scopes,mapper);
        return http.securityMatcher("/api/integracion/erp/**")
                .csrf(c -> c.disable()).cors(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(filter,AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET,RequestExportController.PATH,
                        RequestExportController.PATH+"/*").hasAuthority(READ).anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> error(mapper,r,401))
                        .accessDeniedHandler((q,r,x) -> error(mapper,r,403)))
                .build();
    }
    private static void error(ObjectMapper mapper,HttpServletResponse response,int status) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(mapper.writeValueAsString(new ApiError(Instant.now(),status,
                status==401?"UNAUTHENTICATED":"FORBIDDEN","Acceso tecnico no permitido",RequestExportController.PATH,Map.of())));
    }
    static final class ServiceKeyFilter extends OncePerRequestFilter {
        private final byte[] digest;
        private final boolean enabled;
        private final List<SimpleGrantedAuthority> authorities;
        private final ObjectMapper mapper;
        ServiceKeyFilter(String key,String scopes,ObjectMapper mapper) {
            if(!key.isEmpty() && (key.length()<32 || key.length()>512 || !key.matches("[!-~]+")))
                throw new IllegalStateException("Invalid request export key configuration");
            enabled=!key.isEmpty(); digest=hash(key); this.mapper=mapper;
            authorities=Arrays.asList(scopes.split("[,\\s]+")).contains(READ)
                    ? List.of(new SimpleGrantedAuthority(READ)) : List.of();
        }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                throws ServletException,IOException {
            response.setHeader("Cache-Control","no-store");
            var values=Collections.list(request.getHeaders("X-ERP-Service-Key"));
            String supplied=values.size()==1 ? values.getFirst() : null;
            if(!enabled || supplied==null || supplied.length()>512 || !MessageDigest.isEqual(digest,hash(supplied))) {
                error(mapper,response,401); return;
            }
            var context=SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("erp-request-reader",null,authorities));
            SecurityContextHolder.setContext(context);
            try { chain.doFilter(request,response); } finally { SecurityContextHolder.clearContext(); }
        }
        private static byte[] hash(String text) {
            try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
            catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
        @Override public String toString() { return "RequestExportKeyFilter[REDACTED]"; }
    }
}
