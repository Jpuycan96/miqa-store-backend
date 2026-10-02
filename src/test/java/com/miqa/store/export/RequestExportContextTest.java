package com.miqa.store.export;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** Production-style component naming, without Boot/DataSource/Flyway or network. */
class RequestExportContextTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @ComponentScan(basePackageClasses=RequestExportSecurity.class, useDefaultFilters=false,
            includeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE, classes=RequestExportSecurity.class))
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    }
    @Test void componentScanStartsWithoutBeanOverridingAndRegistersOneExportChain() {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of(
                    "app.erp.requests.api-key","", "app.erp.requests.scopes","")));
            context.register(Config.class);
            context.refresh();
            assertThat(context.isActive()).isTrue();
            assertThat(((org.springframework.beans.factory.support.DefaultListableBeanFactory)context.getBeanFactory()).isAllowBeanDefinitionOverriding()).isFalse();
            assertThat(context.getBeanNamesForType(RequestExportSecurity.class)).containsExactly("requestExportSecurity");
            assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).containsExactly("requestExportSecurityFilterChain");
            assertThat(context.getBean("springSecurityFilterChain")).isNotNull();
        }
    }
}
