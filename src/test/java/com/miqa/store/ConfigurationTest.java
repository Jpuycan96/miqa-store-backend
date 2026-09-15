package com.miqa.store;

import com.miqa.store.config.MediaProperties;
import com.miqa.store.config.LocalDatabaseGuard;
import com.miqa.store.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    @Test void mediaReferencesAreIndependentOfFrontendAndDomain() {
        var media = new MediaProperties();
        media.setStoragePath(Path.of(".local", "media"));
        assertThat(media.publicUrl("/images/products/banner.png")).isEqualTo("/images/products/banner.png");
        media.setBaseUrl("https://media.example.test/media/");
        assertThat(media.publicUrl("/images/products/banner.png")).isEqualTo("/images/products/banner.png");
        assertThat(media.publicUrl("/images/hero/sample.png")).isEqualTo("/images/hero/sample.png");
        assertThat(media.publicUrl("products/banner.png")).isEqualTo("https://media.example.test/media/products/banner.png");
        assertThat(media.publicUrl("https://cdn.example.test/x.png")).isEqualTo("https://cdn.example.test/x.png");
        assertThat(media.getStoragePath()).isEqualTo(Path.of(".local", "media"));
    }
    @Test void mediaRejectsUnsafeSchemesAndReferences() {
        var media = new MediaProperties();
        assertThatThrownBy(() -> media.setBaseUrl("file:///tmp")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.publicUrl("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.publicUrl("../private.png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.publicUrl("//outside.example/x.png")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseGuardRejectsERPAndRemoteConnections() {
        var guard = new LocalDatabaseGuard();
        for (String url : new String[]{"jdbc:postgresql://localhost:55432/gigantografias_db", "jdbc:postgresql://remote.example:5432/miqa_store_db"}) {
            assertThatThrownBy(() -> guard.postProcessEnvironment(new MockEnvironment().withProperty("spring.datasource.url",url), new SpringApplication())).isInstanceOf(IllegalStateException.class);
        }
        guard.postProcessEnvironment(new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db"),new SpringApplication());
    }
    @Test void databaseGuardAllowsSharedDevelopmentDatabaseOnLocalProfile() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:5432/miqa_store_dev_db");
        environment.setActiveProfiles("local");

        assertThatCode(() -> new LocalDatabaseGuard().postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }
    @Test void authenticationObjectsNeverPrintCredentials() {
        assertThat(new com.miqa.store.admin.AdminAuthController.Login("admin", "private-password").toString()).doesNotContain("private-password");
        assertThat(new com.miqa.store.admin.AdminAuthController.Session("private-token",java.time.Instant.now()).toString()).doesNotContain("private-token");
    }
    @Test void unexpectedErrorsNeverExposeExceptionDetails() {
        var response = new ApiExceptionHandler().unexpected(new IllegalStateException("private database details"), new MockHttpServletRequest("GET","/api/public/products"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().toString()).doesNotContain("private database details", "IllegalStateException");
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
