package io.github.mishastoyanov.llmprofiles.autoconfigure;

import javax.sql.DataSource;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.accounting.JdbcCallListener;
import io.github.mishastoyanov.llmprofiles.accounting.LogCallListener;
import io.github.mishastoyanov.llmprofiles.accounting.MicrometerCallListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The built-in sinks, each a {@link LlmCallListener} bean like any the application declares.
 * Conditions name classes as strings for the reason on {@link LlmProfilesAutoConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class LlmAccountingConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "llm.accounting.log.enabled", havingValue = "true")
    static class Log {

        @Bean
        LogCallListener llmLogCallListener() {
            return new LogCallListener();
        }
    }

    /**
     * On by default wherever the application has a registry. The registry is looked up at the
     * first call rather than here, so this does not depend on the order auto-configurations run in.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(name = "llm.accounting.metrics.enabled", havingValue = "true", matchIfMissing = true)
    static class Metrics {

        @Bean
        LlmCallListener llmMetricsCallListener(ObjectProvider<MeterRegistry> registry) {
            return new LlmCallListener() {
                private volatile MicrometerCallListener meters;

                @Override
                public void onCall(LlmCallEvent event) {
                    if (meters == null) {
                        MeterRegistry found = registry.getIfAvailable();
                        if (found == null) {
                            return;
                        }
                        meters = new MicrometerCallListener(found);
                    }
                    meters.onCall(event);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(name = "llm.accounting.jdbc.enabled", havingValue = "true")
    static class Jdbc {

        @Bean
        JdbcCallListener llmJdbcCallListener(LlmProperties properties,
                                             ObjectProvider<JdbcTemplate> templates,
                                             ObjectProvider<DataSource> dataSources) {
            JdbcTemplate jdbc = templates.getIfUnique();
            if (jdbc == null) {
                DataSource dataSource = dataSources.getIfUnique();
                if (dataSource == null) {
                    throw new LlmProfilesConfigurationException("llm.accounting.jdbc.enabled is true, but the"
                            + " application has no single DataSource to write calls to");
                }
                jdbc = new JdbcTemplate(dataSource);
            }
            LlmProperties.Jdbc settings = properties.accounting().jdbc();
            JdbcCallListener listener = new JdbcCallListener(jdbc, settings.table());
            if (settings.createTable()) {
                listener.createTable();
            }
            return listener;
        }
    }
}
