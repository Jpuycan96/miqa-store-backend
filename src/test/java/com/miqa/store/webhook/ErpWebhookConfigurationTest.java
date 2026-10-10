package com.miqa.store.webhook;

import com.miqa.store.erp.ErpCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErpWebhookConfigurationTest {
    @Configuration @Import({ErpWebhookProperties.class, ErpWebhookScheduling.class, ErpWebhookWorker.class})
    static class Config {
        @Bean ErpWebhookQueue queue() { return mock(ErpWebhookQueue.class); }
        @Bean ErpCatalogService synchronizer() { return mock(ErpCatalogService.class); }
    }
    private AnnotationConfigApplicationContext context(boolean enabled, String profile) {
        var context = new AnnotationConfigApplicationContext();
        if (profile != null) context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
                "app.erp.webhook.processing-enabled",String.valueOf(enabled), "app.erp.webhook.queue-interval-ms","3600000")));
        context.register(Config.class); context.refresh(); return context;
    }
    @Test void defaultsDisableReceiverAndDoNotRegisterWorkerOrScheduling() {
        try (var context = context(false,null)) {
            assertThat(context.getBean(ErpWebhookProperties.class).receiveEnabled()).isFalse();
            assertThat(context.getBeansOfType(ErpWebhookWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            verifyNoInteractions(context.getBean(ErpWebhookQueue.class),context.getBean(ErpCatalogService.class));
        }
    }
    @Test void enabledProcessingRegistersWorkerButBootstrapProfileNeverSchedulesIt() {
        try (var context = context(true,null)) {
            assertThat(context.getBeansOfType(ErpWebhookWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
            verifyNoInteractions(context.getBean(ErpWebhookQueue.class),context.getBean(ErpCatalogService.class));
        }
        try (var context = context(true,"admin-bootstrap")) {
            assertThat(context.getBeansOfType(ErpWebhookWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        }
    }
    @Test void unsafeAuthenticationOrAggressiveQueueIntervalsFailWithoutPrintingSecrets() {
        assertThatThrownBy(() -> new ErpWebhookProperties(true,false,"",300,5000,3,200,30,900,6))
                .hasMessage("Invalid webhook authentication configuration");
        assertThatThrownBy(() -> new ErpWebhookProperties(true,true,"bad-secret",300,5000,3,200,30,900,6))
                .hasMessage("Invalid webhook authentication configuration").hasMessageNotContaining("bad-secret");
        assertThatThrownBy(() -> new ErpWebhookProperties(true,true,SECRET,300,1,3,200,30,900,6))
                .hasMessage("Invalid webhook processing configuration");
        assertThat(properties(true,true).toString()).doesNotContain(SECRET);
    }
}
