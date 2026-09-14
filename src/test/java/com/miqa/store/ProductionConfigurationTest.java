package com.miqa.store;

import com.miqa.store.config.CorsConfiguration;
import com.miqa.store.config.LocalDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTest {
    private MockEnvironment production() throws Exception {
        var env = new MockEnvironment()
                .withProperty("DB_HOST", "127.0.0.1").withProperty("DB_PORT", "5432")
                .withProperty("DB_NAME", "miqa_store_db").withProperty("DB_USERNAME", "test-config-only")
                .withProperty("DB_PASSWORD", "test-config-only")
                // Fixed test-only value: never used to connect to a production database.
                .withProperty("ADMIN_JWT_SECRET", "dGVzdC1vbmx5LXNlY3JldC1taXFhLWFkbWluLTMya2V5ISE=")
                .withProperty("ADMIN_JWT_EXPIRATION", "PT1H")
                .withProperty("APP_CORS_ALLOWED_ORIGINS", "https://store.solucionesmicaela.com,https://other.example.test")
                .withProperty("MEDIA_STORAGE_PATH", "/opt/miqa-store/media")
                .withProperty("MEDIA_BASE_URL", "https://api-store.solucionesmicaela.com/media");
        env.setActiveProfiles("prod");
        env.getPropertySources().addLast(new ResourcePropertySource("classpath:application-prod.properties"));
        env.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        return env;
    }
    private void validate(MockEnvironment env) { new LocalDatabaseGuard().postProcessEnvironment(env, new SpringApplication()); }

    @Test void realProductionPropertiesRequireConfigurationAndKeepSafeDefaults() throws Exception {
        var env = production();
        validate(env);
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://127.0.0.1:5432/miqa_store_db");
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
    @Test void rejectsMissingPlaceholderAndWeakSecretsBeforeDatabaseInitialization() throws Exception {
        for (String name : new String[]{"ADMIN_JWT_SECRET", "DB_PASSWORD", "APP_CORS_ALLOWED_ORIGINS", "MEDIA_STORAGE_PATH"}) {
            var env = production();
            ((java.util.Map<?, ?>) env.getPropertySources().get("mockProperties").getSource()).remove(name);
            assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        }
        for (String secret : new String[]{"CHANGE_ME", "", "short", "c2hvcnQ="}) {
            var env = production().withProperty("ADMIN_JWT_SECRET", secret);
            assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void productionCannotUseTestsERPRemoteDatabaseOrUnsafeDdlAndBind() throws Exception {
        for (var entry : java.util.Map.of("DB_NAME", "miqa_store_test_db", "DB_HOST", "external.test",
                "server.address", "0.0.0.0", "spring.jpa.hibernate.ddl-auto", "update",
                "spring.flyway.enabled", "false", "APP_CORS_ALLOWED_ORIGINS", "http://localhost:4200").entrySet()) {
            var env = production().withProperty(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        }
        var env = production(); env.setActiveProfiles("prod", "test");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void bootstrapRequiresExplicitProductionNonWebModeAndNoFlyway() throws Exception {
        var env = production(); env.setActiveProfiles("prod", "admin-bootstrap");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
        env.withProperty("spring.main.web-application-type", "none").withProperty("spring.flyway.enabled", "false");
        validate(env);
        env.setActiveProfiles("admin-bootstrap");
        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRejectsMalformedAndOutOfRangeTokenExpiration() throws Exception {
        for (String expiration : new String[]{"not-a-duration", "PT0S", "PT25H"}) {
            var env = production().withProperty("ADMIN_JWT_EXPIRATION", expiration);
            assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_JWT_EXPIRATION");
        }
    }
    @Test void corsAcceptsMultipleExactOriginsButRejectsWildcardsAndNonOrigins() {
        assertThatCode(() -> new CorsConfiguration("https://store.example.test, http://localhost:4200")).doesNotThrowAnyException();
        for (String origin : new String[]{"*", "https://*.example.test", "null", "https://host.test/path", "https://user:password@host.test", "https://host.test?x=1"}) {
            assertThatThrownBy(() -> new CorsConfiguration(origin)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
