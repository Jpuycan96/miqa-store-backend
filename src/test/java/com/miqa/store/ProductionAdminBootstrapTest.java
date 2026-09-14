package com.miqa.store;

import com.miqa.store.admin.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class ProductionAdminBootstrapTest {
    @Autowired AdminUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    private static final String PASSWORD = "Test-only-bootstrap-password";

    @Test void manualBootstrapCreatesOnlyFirstHashedAccountAndNeverOverwrites() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("miqa_store_test_db");
        assertThat(users.count()).isZero();
        var runner = new ProductionAdminBootstrap(users, encoder, entityManager, "bootstrap-test", PASSWORD);
        runner.run(new DefaultApplicationArguments());
        var user = users.findByUsername("bootstrap-test").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(users.count()).isEqualTo(1);
    }
    @Test void invalidCredentialsDoNotWriteAnything() {
        assertThat(users.count()).isZero();
        for (String password : new String[]{"", "short", "x".repeat(73)}) {
            var runner = new ProductionAdminBootstrap(users, encoder, entityManager, "bootstrap-test", password);
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        }
        assertThat(users.count()).isZero();
    }
    @Test void ordinaryStartupDoesNotRegisterBootstrapRunner() {
        assertThat(context.getBeansOfType(ProductionAdminBootstrap.class)).isEmpty();
        assertThat(context.getBeansOfType(LocalAdminBootstrap.class)).isEmpty();
    }
}
