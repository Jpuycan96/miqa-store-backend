package com.miqa.store.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unrelated databases before Flyway/DataSource creation, including on the VPS. */
public class LocalDatabaseGuard implements EnvironmentPostProcessor, Ordered {
    private static final String TEST_URL = "jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db";
    private static final String TEST_USER = "miqa_store_local";
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("admin-bootstrap") && (!environment.matchesProfiles("prod")
                || !"none".equals(environment.getProperty("spring.main.web-application-type"))
                || !"false".equals(environment.getProperty("spring.flyway.enabled")))) {
            throw new IllegalStateException("admin-bootstrap requires prod, non-web mode and Flyway disabled");
        }
        if (environment.matchesProfiles("prod")) validateProduction(environment);
        if (environment.matchesProfiles("test")) {
            validateTest(environment);
            return;
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/miqa_store_(?:dev_|test_)?db")) {
            throw new IllegalStateException("Only local miqa_store_db, miqa_store_dev_db or miqa_store_test_db is allowed in this stage");
        }
        if (environment.matchesProfiles("prod") && !url.endsWith("/miqa_store_db")) {
            throw new IllegalStateException("Production requires miqa_store_db; non-production databases are forbidden");
        }
    }
    private void validateTest(ConfigurableEnvironment environment) {
        if (environment.matchesProfiles("local", "prod")) throw new IllegalStateException("test cannot be combined with local/prod");
        // Same relaxed binding as Boot's configuration properties, including environment variable aliases.
        // ConfigData has already loaded; no ApplicationContext/DataSource/Flyway beans exist yet.
        var binder = Binder.get(environment);
        try {
            require(binder, "spring.datasource.url", TEST_URL, true);
            require(binder, "spring.datasource.username", TEST_USER, true);
            require(binder, "spring.datasource.hikari.jdbc-url", TEST_URL, false);
            require(binder, "spring.flyway.url", TEST_URL, false);
            require(binder, "spring.datasource.hikari.username", TEST_USER, false);
            require(binder, "spring.flyway.user", TEST_USER, false);
            require(binder, "spring.datasource.type", "com.zaxxer.hikari.HikariDataSource", false);
            require(binder, "spring.datasource.driver-class-name", "org.postgresql.Driver", false);
            require(binder, "spring.datasource.hikari.driver-class-name", "org.postgresql.Driver", false);
            // Supported by installed Boot/Hikari, but not part of our TEST connection contract.
            for (String property : new String[]{"spring.datasource.jndi-name",
                    "spring.datasource.hikari.data-source-j-n-d-i", "spring.datasource.hikari.data-source-class-name"}) {
                if (binder.bind(property, String.class).isBound()) throw new IllegalStateException();
            }
            for (String property : new String[]{"spring.datasource.hikari.data-source-properties", "spring.flyway.jdbc-properties"}) {
                if (!binder.bind(property, Bindable.mapOf(String.class, String.class)).orElse(java.util.Map.of()).isEmpty()) {
                    throw new IllegalStateException();
                }
            }
        } catch (RuntimeException exception) {
            // Bind errors and URLs may contain credentials: never include the value or original cause.
            throw new IllegalStateException("test requires only 127.0.0.1:55432/miqa_store_test_db as miqa_store_local; alternate connection configuration is forbidden");
        }
    }
    private void require(Binder binder, String property, String expected, boolean required) {
        String actual = binder.bind(property, String.class).orElse(null);
        if (actual == null ? required : !expected.equals(actual)) throw new IllegalStateException();
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
