package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code llm.*} configuration. Vendors say where and how to connect; profiles say which model
 * solves which task and with what parameters; prices say what a model costs; accounting says where
 * the record of every call goes; sources say where else vendors, profiles and prices are read from.
 *
 * @param prices per vendor name, then per model: what the model costs. A model without an entry
 *               is unpriced, which is reported as such and never as free
 */
@ConfigurationProperties("llm")
public record LlmProperties(
        Map<String, Vendor> vendors,
        Map<String, Profile> profiles,
        Map<String, Map<String, Price>> prices,
        @DefaultValue Accounting accounting,
        @DefaultValue Source source) {

    public LlmProperties {
        vendors = ordered(vendors);
        profiles = ordered(profiles);
        prices = ordered(prices);
    }

    /** What can change while the application runs. */
    public LlmSettings settings() {
        return new LlmSettings(vendors, profiles, prices);
    }

    /**
     * @param type       which adapter connects to it: {@code anthropic}, {@code openai-compatible},
     *                   {@code ollama}, {@code webhook}, or the type of an adapter the application
     *                   declares itself
     * @param baseUrl    null leaves the vendor SDK's own default; required for a webhook
     * @param auth       webhook only: a header sent with every request
     * @param timeout    how long to wait for the vendor: for a call, the whole answer; for a
     *                   stream, each piece of it. Each retry waits as long again. Null leaves the
     *                   vendor SDK's default, and 60 seconds for a webhook
     * @param maxRetries how often a failed call is tried again; null leaves the vendor's default
     *                   (2 for the Anthropic and OpenAI SDKs, 10 with growing pauses for Spring AI's
     *                   Ollama). A webhook is never retried
     */
    public record Vendor(String type, String apiKey, String baseUrl, Auth auth, Duration timeout, Integer maxRetries) {
    }

    public record Auth(String header, String value) {
    }

    /**
     * @param model       required by Spring AI vendors; a webhook passes it to the template, if
     *                    the template asks for it
     * @param temperature null leaves the provider's default
     * @param maxTokens   null leaves Spring AI's default for the vendor
     * @param reasoning   how much the model is to think; null leaves the model's default. See
     *                    {@link Reasoning} for what each vendor type makes of it
     * @param path        webhook only: appended to the vendor's {@code base-url}
     * @param request     webhook only: the JSON body, with {@code {{prompt}}} and friends filled in
     * @param response    webhook only: where in the answer the text and the usage are
     * @param tags        put on every call through the profile, for whoever reads the record of it;
     *                    a client's own tags win over these
     */
    public record Profile(
            String vendor,
            String model,
            Double temperature,
            Integer maxTokens,
            Reasoning reasoning,
            String path,
            String request,
            Response response,
            Map<String, String> tags) {

        public Profile {
            tags = ordered(tags);
        }
    }

    /** Paths such as {@code $.output} or {@code $.choices[0].text}; {@code $} is the whole body. */
    public record Response(String text, String inputTokens, String outputTokens) {
    }

    /** Per million tokens, in whatever currency the configuration is written in; both are required. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }

    public record Accounting(@DefaultValue Log log, @DefaultValue Metrics metrics, @DefaultValue Jdbc jdbc,
                             @DefaultValue Async async) {
    }

    /**
     * Hands events to listeners on a thread of their own, so that a slow sink does not slow calls.
     *
     * @param queueCapacity events waiting for delivery at most; when the queue is full, the call
     *                      that made one more delivers it itself, so nothing is lost
     */
    public record Async(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("10000") int queueCapacity) {
    }

    /** One line per call on the logger {@code io.github.mishastoyanov.llmprofiles.calls}. */
    public record Log(@DefaultValue("false") boolean enabled) {
    }

    /** Meters in the application's Micrometer registry, when it has one. */
    public record Metrics(@DefaultValue("true") boolean enabled) {
    }

    /**
     * One row per call in the application's database.
     *
     * @param table       may carry a schema, as in {@code accounting.llm_call}
     * @param createTable runs {@code CREATE TABLE IF NOT EXISTS} at startup; for trying things
     *                    out, while a real database gets the table from its migrations
     */
    public record Jdbc(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("llm_call") String table,
            @DefaultValue("false") boolean createTable) {
    }

    /**
     * Where vendors, profiles and prices are read from besides this configuration; see
     * {@link LlmConfigSource}.
     *
     * @param refreshInterval how often to {@link LlmProfiles#refresh() refresh}; null for never,
     *                        leaving it to the application
     */
    public record Source(Duration refreshInterval, @DefaultValue SourceJdbc jdbc) {
    }

    /**
     * Vendors, profiles and prices from database tables; see
     * {@link io.github.mishastoyanov.llmprofiles.source.JdbcConfigSource}.
     *
     * @param tablePrefix  put before {@code vendor}, {@code profile} and {@code price}; may carry
     *                     a schema, as in {@code config.llm_}
     * @param createTables runs {@code CREATE TABLE IF NOT EXISTS} at startup, for trying things out
     */
    public record SourceJdbc(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("llm_") String tablePrefix,
            @DefaultValue("false") boolean createTables) {
    }

    /** Declaration order is kept: it is the order problems and listings are reported in. */
    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
