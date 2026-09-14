package com.miqa.store.admin;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit one-shot process; never registered during an ordinary prod startup. */
@Component
@Profile("prod & admin-bootstrap")
public class ProductionAdminBootstrap implements ApplicationRunner {
    private final AdminUserRepository users;
    private final PasswordEncoder encoder;
    private final EntityManager entityManager;
    private final String username;
    private final String password;

    public ProductionAdminBootstrap(AdminUserRepository users, PasswordEncoder encoder, EntityManager entityManager,
            @Value("${ADMIN_BOOTSTRAP_USERNAME:}") String username,
            @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.entityManager = entityManager;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!username.matches("[a-zA-Z0-9._-]{3,100}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Bootstrap requires username (3-100 allowed characters) and password (12 characters minimum, 72 UTF-8 bytes maximum)");
        }
        // Serialize first-user creation even if two operators start the command together.
        entityManager.createNativeQuery("LOCK TABLE admin_users IN EXCLUSIVE MODE").executeUpdate();
        if (users.count() != 0) throw new IllegalStateException("Admin already exists; bootstrap will not overwrite or add accounts");
        users.saveAndFlush(new AdminUser(username, encoder.encode(password)));
    }
}
