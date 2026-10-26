package io.github.mishastoyanov.llmprofiles;

import java.time.Duration;
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
     *                {@code ollama}, {@code webhook}, or the type of an adapter the application
     *                declares itself
     * @param baseUrl null leaves the vendor SDK's own default; required for a webhook
     * @param auth    webhook only: a header sent with every request
     * @param timeout webhook only for now; null means 60 seconds
     */
    public record Vendor(String type, String apiKey, String baseUrl, Auth auth, Duration timeout) {
    }

    public record Auth(String header, String value) {
    }

    /**
     * @param model       required by Spring AI vendors; a webhook passes it to the template, if
     *                    the template asks for it
     * @param temperature null leaves the provider's default
     * @param maxTokens   null leaves Spring AI's default for the vendor
     * @param path        webhook only: appended to the vendor's {@code base-url}
     * @param request     webhook only: the JSON body, with {@code {{prompt}}} and friends filled in
     * @param response    webhook only: where in the answer the text and the usage are
     */
    public record Profile(
            String vendor,
            String model,
            Double temperature,
            Integer maxTokens,
            String path,
            String request,
            Response response) {
    }

    /** Paths such as {@code $.output} or {@code $.choices[0].text}; {@code $} is the whole body. */
    public record Response(String text, String inputTokens, String outputTokens) {
    }

    /** Declaration order is kept: it is the order problems and listings are reported in. */
    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
