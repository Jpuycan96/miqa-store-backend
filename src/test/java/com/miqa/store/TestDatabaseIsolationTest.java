package com.miqa.store;

import com.miqa.store.config.LocalDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Pure environment/property-binding tests: never run SpringApplication or create a DataSource. */
class TestDatabaseIsolationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db";
    private MockEnvironment testEnvironment() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "miqa_store_local");
        environment.setActiveProfiles("test");
        return environment;
    }
    private void validate(MockEnvironment environment) {
        new LocalDatabaseGuard().postProcessEnvironment(environment, new SpringApplication());
    }
    @Test void acceptsOnlyTheExactTestDestinationIncludingMatchingAlternateUrls() {
        var environment = testEnvironment().withProperty("spring.datasource.hikari.jdbc-url", URL)
                .withProperty("spring.flyway.url", URL);
        assertThatCode(() -> validate(environment)).doesNotThrowAnyException();
    }
    @Test void rejectsEveryUnsafeDestinationThroughEachUrlProperty() {
        for (String property : new String[]{"spring.datasource.url", "spring.datasource.hikari.jdbc-url", "spring.flyway.url"}) {
            for (String url : new String[]{URL.replace("miqa_store_test_db", "miqa_store_db"),
                    URL.replace("miqa_store_test_db", "miqa_store_dev_db"), URL.replace("miqa_store_test_db", "gigantografias_db"),
                    URL.replace("55432", "5432"), URL.replace("127.0.0.1", "remote.example"),
                    URL.replace("127.0.0.1", "localhost"), URL + "?currentSchema=other", ""}) {
                assertThatThrownBy(() -> validate(testEnvironment().withProperty(property, url)))
                        .as(property + " must not redirect TEST").isInstanceOf(IllegalStateException.class);
            }
        }
    }
    @Test void rejectsBothJndiEntryPointsEvenWithAnOtherwiseSafeUrl() {
        for (String property : new String[]{"spring.datasource.jndi-name", "spring.datasource.hikari.data-source-j-n-d-i"}) {
            assertThatThrownBy(() -> validate(testEnvironment().withProperty(property, "java:comp/env/jdbc/other")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rejectsHikariDatasourceClassAndConnectionPropertyMaps() {
        for (var entry : Map.of("spring.datasource.hikari.data-source-class-name", "org.postgresql.ds.PGSimpleDataSource",
                "spring.datasource.hikari.data-source-properties.databaseName", "miqa_store_db",
                "spring.flyway.jdbc-properties.PGDBNAME", "miqa_store_dev_db").entrySet()) {
            assertThatThrownBy(() -> validate(testEnvironment().withProperty(entry.getKey(), entry.getValue())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void enforcesTestUserAndTheSupportedDriverAndPool() {
        for (String property : new String[]{"spring.datasource.username", "spring.datasource.hikari.username", "spring.flyway.user",
                "spring.datasource.driver-class-name", "spring.datasource.hikari.driver-class-name", "spring.datasource.type"}) {
            assertThatThrownBy(() -> validate(testEnvironment().withProperty(property, "other"))).isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> validate(testEnvironment().withProperty("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource")
                .withProperty("spring.datasource.driver-class-name", "org.postgresql.Driver"))).doesNotThrowAnyException();
    }
    @Test void relaxedCamelCaseAndEnvironmentAliasesCannotBypassTheGuard() {
        for (String property : new String[]{"spring.datasource.hikari.jdbcUrl", "spring.datasource.hikari.dataSourceJNDI"}) {
            var environment = testEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of(property, "unsafe")));
            assertThatThrownBy(() -> validate(environment)).isInstanceOf(IllegalStateException.class);
        }
        for (String property : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_HIKARI_JDBCURL", "SPRING_FLYWAY_URL",
                "SPRING_DATASOURCE_JNDINAME", "SPRING_DATASOURCE_HIKARI_DATASOURCEJNDI"}) {
            var environment = testEnvironment();
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(property, "unsafe")));
            assertThatThrownBy(() -> validate(environment)).as(property).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void higherPriorityOverrideIsCheckedInsteadOfTheSafeFallback() {
        var environment = testEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties", Map.of("spring.flyway.url", URL.replace("test_", "dev_"))));
        assertThatThrownBy(() -> validate(environment)).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidValuesAndBindingFailuresNeverExposeCredentials() {
        for (String value : new String[]{URL + "?password=private-password", "${MISSING_SECRET}"}) {
            assertThatThrownBy(() -> validate(testEnvironment().withProperty("spring.flyway.url", value)))
                    .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("private-password");
        }
    }
    @Test void realTestPropertiesIgnoreGenericAndLegacyDatabaseVariables() throws Exception {
        var environment = new MockEnvironment().withProperty("DB_NAME", "miqa_store_db").withProperty("DB_HOST", "other")
                .withProperty("DB_PORT", "5432").withProperty("DB_USERNAME", "other")
                .withProperty("TEST_DB_PORT", "5433").withProperty("TEST_DB_USERNAME", "other");
        environment.setActiveProfiles("test");
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application-test.properties"));
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(URL);
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("miqa_store_local");
        assertThatCode(() -> validate(environment)).doesNotThrowAnyException();
    }
    @Test void rejectsMissingDestinationAndMixedProfiles() {
        var missing = new MockEnvironment(); missing.setActiveProfiles("test");
        assertThatThrownBy(() -> validate(missing)).isInstanceOf(IllegalStateException.class);
        for (String profile : new String[]{"local", "prod"}) {
            var environment = testEnvironment(); environment.setActiveProfiles("test", profile);
            assertThatThrownBy(() -> validate(environment)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void localAndDefaultProfilesRetainTheirExistingConnectionRules() {
        for (String profile : new String[]{"local", "default"}) {
            var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/miqa_store_dev_db");
            environment.setActiveProfiles(profile);
            assertThatCode(() -> validate(environment)).doesNotThrowAnyException();
        }
    }
    @Test void registeredEnvironmentProcessorRunsAfterConfigDataBeforeBeanCreation() throws Exception {
        var properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/META-INF/spring.factories")) { properties.load(input); }
        assertThat(properties.getProperty("org.springframework.boot.EnvironmentPostProcessor")).contains(LocalDatabaseGuard.class.getName());
        assertThat(new LocalDatabaseGuard().getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }
}
