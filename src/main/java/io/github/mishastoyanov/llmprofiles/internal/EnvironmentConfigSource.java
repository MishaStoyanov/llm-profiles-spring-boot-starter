package io.github.mishastoyanov.llmprofiles.internal;

import io.github.mishastoyanov.llmprofiles.LlmConfigSource;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The application's own {@code llm.*}, bound afresh on every load, so a refresh also sees what
 * changed in the environment, as after a Spring Cloud Config refresh.
 */
public final class EnvironmentConfigSource implements LlmConfigSource {

    private final Environment environment;

    public EnvironmentConfigSource(Environment environment) {
        this.environment = environment;
    }

    @Override
    public LlmSettings load() {
        return Binder.get(environment).bind("llm", LlmProperties.class)
                .map(LlmProperties::settings)
                .orElse(LlmSettings.EMPTY);
    }
}
