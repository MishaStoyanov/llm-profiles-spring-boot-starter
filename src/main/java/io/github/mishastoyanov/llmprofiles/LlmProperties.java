package io.github.mishastoyanov.llmprofiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code llm.*} configuration. Vendors say where and how to connect; profiles say which model
 * solves which task and with what parameters.
 */
@ConfigurationProperties("llm")
public record LlmProperties(Map<String, Vendor> vendors, Map<String, Profile> profiles) {

    public LlmProperties {
        vendors = ordered(vendors);
        profiles = ordered(profiles);
    }

    /**
     * @param type    which adapter connects to it: {@code anthropic}, {@code openai-compatible},
     *                {@code ollama}, or the type of an adapter the application declares itself
     * @param baseUrl null leaves the vendor SDK's own default
     */
    public record Vendor(String type, String apiKey, String baseUrl) {
    }

    /**
     * @param temperature null leaves the provider's default
     * @param maxTokens   null leaves Spring AI's default for the vendor
     */
    public record Profile(String vendor, String model, Double temperature, Integer maxTokens) {
    }

    /** Declaration order is kept: it is the order problems and listings are reported in. */
    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
