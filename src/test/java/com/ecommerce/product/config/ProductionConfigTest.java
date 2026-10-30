package com.ecommerce.product.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience-relevant settings of the packaged {@code application.yaml}, read as the running service reads them
 * (no profile, no override). The ITs run with test overrides on purpose; this pins what production gets.
 */
class ProductionConfigTest {

    private static Binder binder;

    @BeforeAll
    static void load() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        binder = new Binder(ConfigurationPropertySources.from(sources));
    }

    private static <T> T get(String name, Class<T> type) {
        return binder.bind(name, Bindable.of(type)).orElseThrow(() -> new AssertionError("application.yaml has no " + name));
    }

    @Test
    void database_waitsForAConnectionAtMost5s_andHoldsNoConnectionOutsideATransaction() {
        assertThat(get("spring.datasource.hikari.connection-timeout", Integer.class)).isEqualTo(5000);
        assertThat(get("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    void database_driverBoundsConnectAndRead_soAFrozenDatabaseCannotHoldAThreadForMinutes() {
        Map<String, String> driver = binder.bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class)).get();

        assertThat(driver).containsEntry("connectTimeout", "5")
                .containsEntry("socketTimeout", "30")
                .containsEntry("tcpKeepAlive", "true");
    }

    @Test
    void logging_isInfo_inOneFormat_withTheUnthrottledFrameworkChatterOff() {
        Map<String, String> levels = binder.bind("logging.level", Bindable.mapOf(String.class, String.class)).get();

        assertThat(get("logging.pattern.console", String.class))
                .isEqualTo("%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId:-}] [%X{traceId:-}] %-5level %logger{36} - %msg%n");
        assertThat(levels).containsEntry("com.ecommerce", "INFO")
                .containsEntry("org.hibernate.orm.jdbc.error", "OFF")
                .containsEntry("org.springframework.transaction.interceptor.TransactionInterceptor", "OFF");
    }
}
