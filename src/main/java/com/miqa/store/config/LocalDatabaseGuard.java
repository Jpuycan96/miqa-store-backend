package com.miqa.store.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unrelated databases before Flyway/DataSource creation, including on the VPS. */
public class LocalDatabaseGuard implements EnvironmentPostProcessor, Ordered {
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("admin-bootstrap") && (!environment.matchesProfiles("prod")
                || !"none".equals(environment.getProperty("spring.main.web-application-type"))
                || !"false".equals(environment.getProperty("spring.flyway.enabled")))) {
            throw new IllegalStateException("admin-bootstrap requires prod, non-web mode and Flyway disabled");
        }
        if (environment.matchesProfiles("prod")) validateProduction(environment);
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/miqa_store_(?:test_)?db")) {
            throw new IllegalStateException("Only local miqa_store_db or miqa_store_test_db is allowed in this stage");
        }
        if (environment.matchesProfiles("prod") && !url.endsWith("/miqa_store_db")) {
            throw new IllegalStateException("Production requires miqa_store_db; test databases are forbidden");
        }
    }
    private void validateProduction(ConfigurableEnvironment environment) {
        if (environment.matchesProfiles("local", "test")) {
            throw new IllegalStateException("prod cannot be combined with local or test");
        }
        for (String name : new String[]{"spring.datasource.username", "spring.datasource.password",
                "app.admin.jwt-secret", "app.admin.jwt-expiration", "miqa.cors.allowed-origins",
                "app.media.storage-path", "app.media.base-url"}) {
            String value;
            try { value = environment.getProperty(name); }
            catch (IllegalArgumentException exception) { throw new IllegalStateException("Missing production configuration: " + name); }
            if (value == null || value.isBlank() || value.contains("CHANGE_ME")) {
                throw new IllegalStateException("Missing production configuration: " + name);
            }
        }
        try {
            if (java.util.Base64.getDecoder().decode(environment.getProperty("app.admin.jwt-secret")).length < 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("ADMIN_JWT_SECRET must be base64 of at least 32 random bytes");
        }
        try {
            var expiration = java.time.Duration.parse(environment.getProperty("app.admin.jwt-expiration"));
            if (expiration.compareTo(java.time.Duration.ofMinutes(1)) < 0
                    || expiration.compareTo(java.time.Duration.ofHours(24)) > 0) throw new IllegalArgumentException();
        } catch (java.time.DateTimeException | IllegalArgumentException exception) {
            throw new IllegalStateException("ADMIN_JWT_EXPIRATION must be an ISO-8601 duration between PT1M and PT24H");
        }
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"8082".equals(environment.getProperty("server.port"))
                || !"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                || !"true".equals(environment.getProperty("spring.flyway.clean-disabled"))
                || (!environment.matchesProfiles("admin-bootstrap") && !"true".equals(environment.getProperty("spring.flyway.enabled")))) {
            throw new IllegalStateException("prod requires loopback:8082, Hibernate validate and safe Flyway settings");
        }
        if (!environment.getProperty("app.media.storage-path").startsWith("/")) {
            throw new IllegalStateException("MEDIA_STORAGE_PATH must be an absolute Linux path in prod");
        }
        for (String origin : environment.getProperty("miqa.cors.allowed-origins").split(",")) {
            if (!CorsConfiguration.isOrigin(origin.trim()) || !origin.trim().startsWith("https://")) {
                throw new IllegalStateException("Production CORS requires explicit HTTPS origins");
            }
        }
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
