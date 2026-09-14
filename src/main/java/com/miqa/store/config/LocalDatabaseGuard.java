package com.miqa.store.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** This initial backend is local-only. Reject other databases before Flyway/DataSource creation. */
public class LocalDatabaseGuard implements EnvironmentPostProcessor, Ordered {
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}/miqa_store_(?:test_)?db")) {
            throw new IllegalStateException("Only local miqa_store_db or miqa_store_test_db is allowed in this stage");
        }
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
