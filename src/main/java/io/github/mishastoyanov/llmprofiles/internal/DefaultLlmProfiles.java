package io.github.mishastoyanov.llmprofiles.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.UnknownProfileException;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import org.springframework.util.StringUtils;

/**
 * Connects every vendor once and builds one client per profile, all at startup, so a broken
 * configuration stops the application instead of the first request that happens to need it.
 */
public final class DefaultLlmProfiles implements LlmProfiles {

    private final Map<String, LlmClient> clients;
    private final Set<String> names;

    public DefaultLlmProfiles(LlmProperties properties, List<LlmVendorAdapter> adapters, List<LlmCallListener> listeners) {
        List<String> problems = new ArrayList<>();
        Map<String, LlmVendorAdapter> adaptersByType = indexByType(adapters, problems);

        Map<String, LlmVendor> vendors = new LinkedHashMap<>();
        properties.vendors().forEach((name, vendor) -> {
            String type = vendor.type() == null ? null : vendor.type().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(type)) {
                problems.add("vendor '" + name + "': type is not set");
                return;
            }
            LlmVendorAdapter adapter = adaptersByType.get(type);
            if (adapter == null) {
                problems.add("vendor '" + name + "': no adapter for type '" + type + "'. Available: "
                        + adaptersByType.keySet() + ". Vendor modules are optional dependencies,"
                        + " so the one for this type may be missing from the classpath");
                return;
            }
            try {
                vendors.put(name, adapter.connect(name, vendor));
            } catch (RuntimeException e) {
                problems.add("vendor '" + name + "': " + e.getMessage());
            }
        });

        Map<String, LlmClient> clients = new LinkedHashMap<>();
        Map<String, String> declaredBy = new LinkedHashMap<>();
        List<LlmCallListener> fixedListeners = List.copyOf(listeners);
        properties.profiles().forEach((name, profile) -> {
            String clash = declaredBy.putIfAbsent(ProfileNames.canonical(name), name);
            if (clash != null) {
                problems.add("profiles '" + clash + "' and '" + name
                        + "' are one name once case, dashes and underscores are ignored");
                return;
            }
            if (!StringUtils.hasText(profile.vendor())) {
                problems.add("profile '" + name + "': vendor is not set");
                return;
            }
            if (!properties.vendors().containsKey(profile.vendor())) {
                problems.add("profile '" + name + "': vendor '" + profile.vendor()
                        + "' is not declared under llm.vendors. Declared: " + properties.vendors().keySet());
                return;
            }
            LlmVendor vendor = vendors.get(profile.vendor());
            if (vendor == null) {
                return; // the vendor's own problem is already on the list
            }
            List<String> profileProblems = vendor.check(profile);
            if (!profileProblems.isEmpty()) {
                profileProblems.forEach(problem -> problems.add("profile '" + name + "': " + problem));
                return;
            }
            clients.put(ProfileNames.canonical(name), new DefaultLlmClient(name, profile.vendor(), vendor, profile,
                    profile.model(), profile.temperature(), profile.maxTokens(), fixedListeners));
        });

        if (!problems.isEmpty()) {
            throw LlmProfilesConfigurationException.of("Invalid llm configuration:", problems);
        }
        this.clients = Collections.unmodifiableMap(clients);
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(declaredBy.values()));
    }

    /** Matched the way {@code localDraft} finds {@code local-draft}; see {@link ProfileNames#canonical}. */
    @Override
    public LlmClient profile(String name) {
        LlmClient client = name == null ? null : clients.get(ProfileNames.canonical(name));
        if (client == null) {
            throw new UnknownProfileException(name, names);
        }
        return client;
    }

    /** As written in the configuration. */
    @Override
    public Set<String> names() {
        return names;
    }

    private static Map<String, LlmVendorAdapter> indexByType(List<LlmVendorAdapter> adapters, List<String> problems) {
        Map<String, LlmVendorAdapter> byType = new LinkedHashMap<>();
        for (LlmVendorAdapter adapter : adapters) {
            String type = adapter.type().toLowerCase(Locale.ROOT);
            LlmVendorAdapter previous = byType.putIfAbsent(type, adapter);
            if (previous != null) {
                problems.add("two adapters claim vendor type '" + type + "': "
                        + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
        return byType;
    }
}
