package com.miqa.store.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!admin-bootstrap")
@ConditionalOnProperty(name="app.erp.webhook.processing-enabled", havingValue="true")
public class ErpWebhookScheduling {}
