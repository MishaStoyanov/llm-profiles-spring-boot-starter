package io.github.mishastoyanov.llmprofiles.vendor.webhook;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON body of a webhook request, with {@code {{name}}} placeholders.
 * <p>
 * A placeholder becomes a whole JSON value: a string arrives quoted and escaped, a number as a
 * number, a missing value as {@code null}. So it is written without quotes, {@code {"prompt": {{prompt}}}},
 * and a prompt holding quotes or line breaks cannot break the body. Pasting raw text between
 * quotes, the way most templates do, would.
 * <p>
 * Deliberately not a template engine: there are no expressions, loops or conditions.
 */
final class RequestTemplate {

    static final List<String> VARIABLES = List.of("prompt", "profile", "model", "temperature", "max-tokens", "reasoning");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}\\s]*)\\s*}}");

    private final String template;
    private final JsonMapper json;

    private RequestTemplate(String template, JsonMapper json) {
        this.template = template;
        this.json = json;
    }

    /**
     * @throws IllegalArgumentException naming the unknown placeholders, or saying why the body
     *         would not be JSON once filled in
     */
    static RequestTemplate parse(String template, JsonMapper json) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!VARIABLES.contains(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("request template uses unknown placeholders " + unknown
                    + "; available: " + VARIABLES);
        }
        RequestTemplate parsed = new RequestTemplate(template, json);
        String sample = parsed.render(new Values("sample \"prompt\"", "profile", "model", 0.5, 100, "high"));
        try {
            json.readTree(sample);
        } catch (JacksonException e) {
            String hint = template.contains("\"{{")
                    ? " Placeholders are filled in with JSON values, quotes included: write {{prompt}}, not \"{{prompt}}\"."
                    : "";
            throw new IllegalArgumentException("request template is not valid JSON once filled in: "
                    + e.getOriginalMessage() + "." + hint);
        }
        return parsed;
    }

    /** @param reasoning the level in lower case, as written in the configuration */
    record Values(String prompt, String profile, String model, Double temperature, Integer maxTokens,
                  String reasoning) {

        Map<String, Object> asMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("prompt", prompt);
            map.put("profile", profile);
            map.put("model", model);
            map.put("temperature", temperature);
            map.put("max-tokens", maxTokens);
            map.put("reasoning", reasoning);
            return map;
        }
    }

    boolean uses(String variable) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (matcher.group(1).equals(variable)) {
                return true;
            }
        }
        return false;
    }

    String render(Values values) {
        Map<String, Object> map = values.asMap();
        return PLACEHOLDER.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(json.writeValueAsString(map.get(match.group(1)))));
    }
}
