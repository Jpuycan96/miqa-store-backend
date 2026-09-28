package com.miqa.store;

import com.miqa.store.config.LocalDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Loads actual files exactly as the manual launcher does, without beans, JDBC or Flyway. */
class ManualTestConfigurationTest {
    private StandardEnvironment configuration() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("manual-launch", Map.of(
                "spring.profiles.active", "test",
                "spring.config.location", Path.of("src/main/resources/application.properties").toUri() + ","
                    + Path.of("src/test/resources/application-test.properties").toUri(),
                "server.address", "127.0.0.1", "server.port", "8081")));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    @Test void explicitTestFileSuppliesDatasourceCorsAndTestOnlyJwtWithoutTestClasspathDiscovery() {
        var environment = configuration();
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("miqa_store_local");
        assertThat(environment.getProperty("miqa.cors.allowed-origins")).isEqualTo("http://localhost:4200");
        assertThat(Base64.getDecoder().decode(environment.getProperty("app.admin.jwt-secret"))).hasSizeGreaterThanOrEqualTo(32);
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.port")).isEqualTo("8081");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThatCode(() -> new LocalDatabaseGuard().postProcessEnvironment(environment, new SpringApplication())).doesNotThrowAnyException();
        assertThatThrownBy(() -> environment.getRequiredProperty("spring.datasource.password")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void explicitFilesDoNotBypassGuardForAnyAlternateDatabaseOrConnectionRoute() {
        for (String database : new String[]{"miqa_store_dev_db", "miqa_store_db", "gigantografias_dev", "gigantografias_db"}) {
            for (String property : new String[]{"spring.datasource.url", "spring.datasource.hikari.jdbc-url", "spring.flyway.url"}) {
                var environment = configuration();
                environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of(property,
                        "jdbc:postgresql://127.0.0.1:55432/" + database)));
                assertThatThrownBy(() -> new LocalDatabaseGuard().postProcessEnvironment(environment, new SpringApplication()))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        var remote = configuration();
        remote.getPropertySources().addFirst(new MapPropertySource("remote", Map.of("spring.datasource.url",
                "jdbc:postgresql://remote.example:55432/miqa_store_test_db")));
        assertThatThrownBy(() -> new LocalDatabaseGuard().postProcessEnvironment(remote, new SpringApplication())).isInstanceOf(IllegalStateException.class);
    }
}
