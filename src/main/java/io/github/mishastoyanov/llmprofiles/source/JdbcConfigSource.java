package io.github.mishastoyanov.llmprofiles.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.mishastoyanov.llmprofiles.LlmConfigSource;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmSettings;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vendors, profiles and prices from three tables, {@code llm_vendor}, {@code llm_profile} and
 * {@code llm_price}, whose definitions are in {@value #SCHEMA} on the classpath. Read on every
 * load, so a changed row takes effect on the next refresh.
 * <p>
 * A row is read as the same keys in YAML would be, through Spring's own binding: {@code timeout}
 * is written {@code 60s}, and placeholders are resolved against the application's environment.
 * <p>
 * Secrets are not stored: {@code api_key} and {@code auth_value} must be a placeholder such as
 * {@code ${ANTHROPIC_API_KEY}}, naming an environment variable, a system property or anything
 * else the environment resolves (Vault, a secrets manager). A key written out in full is refused.
 */
public class JdbcConfigSource implements LlmConfigSource {

    public static final String SCHEMA = "io/github/mishastoyanov/llmprofiles/source/llm_config.sql";

    /** A prefix, optionally after a schema; anything else would be pasted into SQL. */
    private static final Pattern PREFIX = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z0-9_]*");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{[^}]+}");

    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final String prefix;

    public JdbcConfigSource(JdbcTemplate jdbc, Environment environment, String tablePrefix) {
        if (tablePrefix == null || !PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("llm.source.jdbc.table-prefix must be a plain name prefix,"
                    + " optionally after a schema, got '" + tablePrefix + "'");
        }
        this.jdbc = jdbc;
        this.environment = environment;
        this.prefix = tablePrefix;
    }

    /** {@code CREATE TABLE IF NOT EXISTS} for all three, from {@value #SCHEMA} with the configured prefix. */
    public void createTables() {
        StringBuilder sql = new StringBuilder();
        for (String line : schema().split("\\R")) {
            int comment = line.indexOf("--");
            sql.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        for (String statement : sql.toString().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.replace("llm_", prefix));
            }
        }
    }

    @Override
    public LlmSettings load() {
        List<String> problems = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();

        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + prefix + "vendor ORDER BY name")) {
            String key = "llm.vendors[" + row.get("name") + "]";
            put(properties, key + ".type", row.get("type"));
            put(properties, key + ".base-url", row.get("base_url"));
            put(properties, key + ".api-key", secret(row, "api_key", problems));
            put(properties, key + ".auth.header", row.get("auth_header"));
            put(properties, key + ".auth.value", secret(row, "auth_value", problems));
            put(properties, key + ".timeout", row.get("timeout"));
            put(properties, key + ".max-retries", row.get("max_retries"));
        }

        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + prefix + "profile ORDER BY name")) {
            String key = "llm.profiles[" + row.get("name") + "]";
            put(properties, key + ".vendor", row.get("vendor"));
            put(properties, key + ".model", row.get("model"));
            put(properties, key + ".temperature", row.get("temperature"));
            put(properties, key + ".max-tokens", row.get("max_tokens"));
            put(properties, key + ".path", row.get("path"));
            put(properties, key + ".request", row.get("request"));
            put(properties, key + ".response.text", row.get("response_text"));
            put(properties, key + ".response.input-tokens", row.get("response_input_tokens"));
            put(properties, key + ".response.output-tokens", row.get("response_output_tokens"));
            tags(row, problems).forEach((tag, value) -> put(properties, key + ".tags[" + tag + "]", value));
        }

        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM " + prefix + "price ORDER BY vendor, model")) {
            String key = "llm.prices[" + row.get("vendor") + "][" + row.get("model") + "]";
            put(properties, key + ".input-per-million", row.get("input_per_million"));
            put(properties, key + ".output-per-million", row.get("output_per_million"));
        }

        if (!problems.isEmpty()) {
            throw LlmProfilesConfigurationException.of("Invalid rows in the " + prefix + "* tables:", problems);
        }
        Binder binder = new Binder(List.of(new MapConfigurationPropertySource(properties)),
                new PropertySourcesPlaceholdersResolver(environment));
        LlmSettings settings;
        try {
            settings = binder.bind("llm", Bindable.of(LlmSettings.class)).orElse(LlmSettings.EMPTY);
        } catch (BindException e) {
            throw new LlmProfilesConfigurationException("rows in the " + prefix + "* tables: " + e.getMessage()
                    + ": " + NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        }
        // Spring leaves a placeholder it cannot resolve as it is, which would go out as the key.
        settings.vendors().forEach((name, vendor) -> {
            unresolved(name, "api_key", vendor.apiKey(), problems);
            unresolved(name, "auth_value", vendor.auth() == null ? null : vendor.auth().value(), problems);
        });
        if (!problems.isEmpty()) {
            throw LlmProfilesConfigurationException.of("Invalid rows in the " + prefix + "* tables:", problems);
        }
        return settings;
    }

    private void unresolved(String vendor, String column, String value, List<String> problems) {
        if (value != null && REFERENCE.matcher(value).find()) {
            problems.add(prefix + "vendor '" + vendor + "': " + column + " " + value
                    + " does not resolve to anything in the environment");
        }
    }

    private String secret(Map<String, Object> row, String column, List<String> problems) {
        Object value = row.get(column);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (!REFERENCE.matcher(text.strip()).matches()) {
            problems.add(prefix + "vendor '" + row.get("name") + "': " + column + " must be a reference such as"
                    + " ${ANTHROPIC_API_KEY}, not the secret itself");
            return null;
        }
        return text.strip();
    }

    private Map<String, String> tags(Map<String, Object> row, List<String> problems) {
        Object value = row.get("tags");
        if (value == null || value.toString().isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> parsed = JsonMapper.shared().readValue(value.toString(), Map.class);
            Map<String, String> tags = new LinkedHashMap<>();
            parsed.forEach((tag, tagValue) -> {
                if (tagValue != null) {
                    tags.put(String.valueOf(tag), String.valueOf(tagValue));
                }
            });
            return tags;
        } catch (JacksonException e) {
            problems.add(prefix + "profile '" + row.get("name") + "': tags must be a JSON object, as in"
                    + " {\"feature\": \"summaries\"}");
            return Map.of();
        }
    }

    private static void put(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private static String schema() {
        try (InputStream in = JdbcConfigSource.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SCHEMA, e);
        }
    }
}
