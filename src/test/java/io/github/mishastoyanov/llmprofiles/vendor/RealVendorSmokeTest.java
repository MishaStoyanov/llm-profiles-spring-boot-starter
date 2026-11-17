package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real call and one real stream per vendor, run only where its credentials are in the
 * environment. Skipped everywhere else, so the build never needs a key or a network.
 * <p>
 * {@code ANTHROPIC_API_KEY}, optionally {@code ANTHROPIC_MODEL}; {@code OPENAI_API_KEY},
 * optionally {@code OPENAI_BASE_URL} and {@code OPENAI_MODEL}; {@code GEMINI_API_KEY}, optionally
 * {@code GEMINI_MODEL}; {@code OLLAMA_BASE_URL}, optionally {@code OLLAMA_MODEL}. The models
 * default to small, cheap ones.
 */
class RealVendorSmokeTest {

    private static final String PROMPT = "Answer with the single word: pong";
    private final List<LlmCallEvent> events = new ArrayList<>();

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void anthropic() {
        smoke(List.of(
                "llm.vendors.v.type=anthropic",
                "llm.vendors.v.api-key=" + System.getenv("ANTHROPIC_API_KEY"),
                "llm.profiles.p.model=" + env("ANTHROPIC_MODEL", "claude-haiku-4-5")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void openAiCompatible() {
        List<String> properties = new ArrayList<>(List.of(
                "llm.vendors.v.type=openai-compatible",
                "llm.vendors.v.api-key=" + System.getenv("OPENAI_API_KEY"),
                "llm.profiles.p.model=" + env("OPENAI_MODEL", "gpt-4o-mini")));
        if (System.getenv("OPENAI_BASE_URL") != null) {
            properties.add("llm.vendors.v.base-url=" + System.getenv("OPENAI_BASE_URL"));
        }
        smoke(properties);
    }

    /**
     * Gemini through its OpenAI-compatible endpoint: openai-compatible against a server that is not
     * OpenAI, which is what the type is for. The default model is one that does not think before
     * answering, since thinking would spend the small token cap on nothing visible.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    void geminiAsOpenAiCompatible() {
        smoke(List.of(
                "llm.vendors.v.type=openai-compatible",
                "llm.vendors.v.base-url=" + env("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/"),
                "llm.vendors.v.api-key=" + System.getenv("GEMINI_API_KEY"),
                "llm.profiles.p.model=" + env("GEMINI_MODEL", "gemini-3.5-flash-lite")));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OLLAMA_BASE_URL", matches = ".+")
    void ollama() {
        smoke(List.of(
                "llm.vendors.v.type=ollama",
                "llm.vendors.v.base-url=" + System.getenv("OLLAMA_BASE_URL"),
                "llm.profiles.p.model=" + env("OLLAMA_MODEL", "llama3.2")));
    }

    /** The cap is small on purpose: a vendor that ignored it would answer with far more than it. */
    private void smoke(List<String> vendorAndModel) {
        List<String> properties = new ArrayList<>(vendorAndModel);
        properties.addAll(List.of("llm.profiles.p.vendor=v", "llm.profiles.p.max-tokens=16", "llm.vendors.v.timeout=60s"));
        run(properties, client -> {
            LlmResponse response = client.call(PROMPT);
            assertThat(response.text()).containsIgnoringCase("pong");
            assertThat(response.usage().inputTokens()).isPositive();
            assertThat(response.usage().outputTokens()).isPositive().isLessThanOrEqualTo(16L);
            assertThat(response.model()).isNotBlank();

            String streamed = String.join("", client.stream(PROMPT).collectList().block());
            assertThat(streamed).containsIgnoringCase("pong");
            LlmCallEvent event = events.getLast();
            assertThat(event.failed()).isFalse();
            assertThat(event.firstTokenLatency()).isNotNull();
            assertThat(event.usage().outputTokens()).as("usage reported by the stream").isPositive();
        });
    }

    private void run(List<String> properties, Consumer<LlmClient> test) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withBean(LlmCallListener.class, () -> events::add)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> test.accept(context.getBean(LlmProfiles.class).profile("p")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
