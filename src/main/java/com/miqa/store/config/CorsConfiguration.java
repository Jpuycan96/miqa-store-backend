package com.miqa.store.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;

@Configuration
public class CorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    public CorsConfiguration(@Value("${miqa.cors.allowed-origins:}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        if (Arrays.stream(this.origins).anyMatch(value -> value.contains("*"))) throw new IllegalArgumentException("CORS requires explicit origins");
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        if (origins.length > 0) registry.addMapping("/api/public/**").allowedOrigins(origins)
                .allowedMethods("GET", "HEAD", "OPTIONS").allowedHeaders("Accept", "Content-Type").allowCredentials(false).maxAge(1800);
        if (origins.length > 0) registry.addMapping("/api/admin/**").allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS").allowedHeaders("Accept", "Content-Type", "Authorization").allowCredentials(false).maxAge(1800);
    }
}
