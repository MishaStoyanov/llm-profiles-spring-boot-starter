package io.github.mishastoyanov.llmprofiles.autoconfigure;

import javax.sql.DataSource;

import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.source.JdbcConfigSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The built-in configuration sources, each a {@link io.github.mishastoyanov.llmprofiles.LlmConfigSource}
 * bean like any the application declares. Conditions name classes as strings for the reason on
 * {@link LlmProfilesAutoConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class LlmSourceConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @ConditionalOnProperty(name = "llm.source.jdbc.enabled", havingValue = "true")
    static class Jdbc {

        @Bean
        JdbcConfigSource llmJdbcConfigSource(LlmProperties properties, Environment environment,
                                             ObjectProvider<JdbcTemplate> templates,
                                             ObjectProvider<DataSource> dataSources) {
            JdbcTemplate jdbc = templates.getIfUnique();
            if (jdbc == null) {
                DataSource dataSource = dataSources.getIfUnique();
                if (dataSource == null) {
                    throw new LlmProfilesConfigurationException("llm.source.jdbc.enabled is true, but the"
                            + " application has no single DataSource to read vendors and profiles from");
                }
                jdbc = new JdbcTemplate(dataSource);
            }
            LlmProperties.SourceJdbc settings = properties.source().jdbc();
            JdbcConfigSource source = new JdbcConfigSource(jdbc, environment, settings.tablePrefix());
            if (settings.createTables()) {
                source.createTables();
            }
            return source;
        }
    }
}
