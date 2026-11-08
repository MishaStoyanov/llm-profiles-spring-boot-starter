package io.github.mishastoyanov.llmprofiles.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmPrice;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.UnknownProfileException;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.StringUtils;

/**
 * Connects every vendor once and checks every profile against its vendor, all at startup, so a
 * broken configuration stops the application instead of the first request that happens to need it.
 * <p>
 * Clients handed out are handles that look their profile up here on every call; see
 * {@link DefaultLlmClient}.
 */
public final class DefaultLlmProfiles implements LlmProfiles {

    private static final Log log = LogFactory.getLog(DefaultLlmProfiles.class);

    private final Map<String, ResolvedProfile> profiles;
    private final Set<String> names;
    private final List<LlmCallListener> listeners;
    private final Map<String, Map<String, LlmPrice>> prices;
    private final Clock clock;

    public DefaultLlmProfiles(LlmProperties properties, List<LlmVendorAdapter> adapters, List<LlmCallListener> listeners) {
        this(properties, adapters, listeners, Clock.systemUTC());
    }

    /** @param clock where "now" comes from, so a test can decide what it is */
    public DefaultLlmProfiles(LlmProperties properties, List<LlmVendorAdapter> adapters, List<LlmCallListener> listeners,
                              Clock clock) {
        this.clock = clock;
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
            if (vendor.timeout() != null && (vendor.timeout().isNegative() || vendor.timeout().isZero())) {
                problems.add("vendor '" + name + "': timeout must be positive, got " + vendor.timeout());
                return;
            }
            if (vendor.maxRetries() != null && vendor.maxRetries() < 0) {
                problems.add("vendor '" + name + "': max-retries cannot be negative, got " + vendor.maxRetries());
                return;
            }
            try {
                vendors.put(name, adapter.connect(name, vendor));
            } catch (RuntimeException e) {
                problems.add("vendor '" + name + "': " + e.getMessage());
            }
        });

        Map<String, ResolvedProfile> resolved = new LinkedHashMap<>();
        Map<String, String> declaredBy = new LinkedHashMap<>();
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
            String type = properties.vendors().get(profile.vendor()).type().toLowerCase(Locale.ROOT);
            resolved.put(ProfileNames.canonical(name), new ResolvedProfile(name, profile.vendor(), type, vendor, profile));
        });

        Map<String, Map<String, LlmPrice>> priceList = new LinkedHashMap<>();
        properties.prices().forEach((vendor, models) -> {
            if (!properties.vendors().containsKey(vendor)) {
                problems.add("prices for vendor '" + vendor + "': no such vendor under llm.vendors. Declared: "
                        + properties.vendors().keySet());
                return;
            }
            Map<String, LlmPrice> byModel = new LinkedHashMap<>();
            (models == null ? Map.<String, LlmProperties.Price>of() : models).forEach((model, price) -> {
                String where = "price of '" + model + "' at vendor '" + vendor + "': ";
                if (price == null || price.inputPerMillion() == null || price.outputPerMillion() == null) {
                    problems.add(where + "both input-per-million and output-per-million are required");
                } else if (price.inputPerMillion().signum() < 0 || price.outputPerMillion().signum() < 0) {
                    problems.add(where + "a price cannot be negative");
                } else {
                    byModel.put(model, new LlmPrice(price.inputPerMillion(), price.outputPerMillion()));
                }
            });
            priceList.put(vendor, Collections.unmodifiableMap(byModel));
        });

        if (!problems.isEmpty()) {
            throw LlmProfilesConfigurationException.of("Invalid llm configuration:", problems);
        }
        this.prices = Collections.unmodifiableMap(priceList);
        this.profiles = Collections.unmodifiableMap(resolved);
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(declaredBy.values()));
        this.listeners = List.copyOf(listeners);
    }

    /** Matched the way {@code localDraft} finds {@code local-draft}; see {@link ProfileNames#canonical}. */
    @Override
    public LlmClient profile(String name) {
        ResolvedProfile current = resolve(name == null ? null : ProfileNames.canonical(name), name);
        return new DefaultLlmClient(current.name(), ProfileNames.canonical(current.name()), this,
                null, null, null, Map.of());
    }

    /** As written in the configuration. */
    @Override
    public Set<String> names() {
        return names;
    }

    /** The profile as it stands now. {@code asked} is the name to report when there is none. */
    ResolvedProfile resolve(String key, String asked) {
        ResolvedProfile current = key == null ? null : profiles.get(key);
        if (current == null) {
            throw new UnknownProfileException(asked, names);
        }
        return current;
    }

    /** The first of the models that has a price at this vendor; null when none has. */
    LlmPrice price(String vendor, String... models) {
        Map<String, LlmPrice> byModel = prices.getOrDefault(vendor, Map.of());
        for (String model : models) {
            LlmPrice price = model == null ? null : byModel.get(model);
            if (price != null) {
                return price;
            }
        }
        return null;
    }

    Instant now() {
        return clock.instant();
    }

    /** Accounting must not be able to break the call it accounts for. */
    void publish(LlmCallEvent event) {
        for (LlmCallListener listener : listeners) {
            try {
                listener.onCall(event);
            } catch (RuntimeException e) {
                log.warn("LlmCallListener " + listener.getClass().getName() + " failed on a call to profile '"
                        + event.profile() + "'; the call itself is unaffected", e);
            }
        }
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
