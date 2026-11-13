package io.github.mishastoyanov.llmprofiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link LlmConfigSource} provides: the part of the {@code llm.*} configuration that can
 * change while the application runs. Accounting and the sources themselves are not in it, since
 * they decide which beans exist.
 *
 * @param vendors  by vendor name
 * @param profiles by profile name
 * @param prices   per vendor name, then per model
 */
public record LlmSettings(
        Map<String, LlmProperties.Vendor> vendors,
        Map<String, LlmProperties.Profile> profiles,
        Map<String, Map<String, LlmProperties.Price>> prices) {

    public static final LlmSettings EMPTY = new LlmSettings(null, null, null);

    public LlmSettings {
        vendors = ordered(vendors);
        profiles = ordered(profiles);
        prices = ordered(prices);
    }

    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
