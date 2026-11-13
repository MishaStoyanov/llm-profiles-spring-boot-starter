package io.github.mishastoyanov.llmprofiles.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmSettings;

/**
 * Lays the settings of several sources over one another: an entry replaces the one of the same
 * name before it, whole. Profiles are matched the way injection matches them, so
 * {@code local_draft} in a database replaces {@code local-draft} in YAML, and keeps the name it was
 * first declared under.
 */
final class SettingsLayers {

    private SettingsLayers() {
    }

    /** Two profiles that are one name within the same source are a problem, not a replacement. */
    static LlmSettings merge(List<LlmSettings> layers, List<String> problems) {
        Map<String, LlmProperties.Vendor> vendors = new LinkedHashMap<>();
        Map<String, String> profileNames = new LinkedHashMap<>();
        Map<String, LlmProperties.Profile> profiles = new LinkedHashMap<>();
        Map<String, Map<String, LlmProperties.Price>> prices = new LinkedHashMap<>();

        for (LlmSettings layer : layers) {
            vendors.putAll(layer.vendors());

            Map<String, String> inThisLayer = new LinkedHashMap<>();
            layer.profiles().forEach((name, profile) -> {
                String key = ProfileNames.canonical(name);
                String clash = inThisLayer.putIfAbsent(key, name);
                if (clash != null) {
                    problems.add("profiles '" + clash + "' and '" + name
                            + "' are one name once case, dashes and underscores are ignored");
                    return;
                }
                profileNames.putIfAbsent(key, name);
                profiles.put(key, profile);
            });

            layer.prices().forEach((vendor, models) -> prices.computeIfAbsent(vendor, v -> new LinkedHashMap<>())
                    .putAll(models == null ? Map.of() : models));
        }

        Map<String, LlmProperties.Profile> byName = new LinkedHashMap<>();
        profiles.forEach((key, profile) -> byName.put(profileNames.get(key), profile));
        return new LlmSettings(vendors, byName, prices);
    }
}
