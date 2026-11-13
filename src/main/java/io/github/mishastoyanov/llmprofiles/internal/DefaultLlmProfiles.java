package io.github.mishastoyanov.llmprofiles.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmConfigSource;
import io.github.mishastoyanov.llmprofiles.LlmPrice;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmSettings;
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
 * What it holds is one immutable snapshot, replaced whole by {@link #refresh()} after the new one
 * has passed the same checks. Clients handed out are handles that look their profile up in the
 * current snapshot on every call; see {@link DefaultLlmClient}.
 */
public final class DefaultLlmProfiles implements LlmProfiles, AutoCloseable {

    private static final Log log = LogFactory.getLog(DefaultLlmProfiles.class);

    private final List<LlmConfigSource> sources;
    private final Map<String, LlmVendorAdapter> adaptersByType;
    private final Set<String> namesInCode;
    private final CallEventDelivery delivery;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private ScheduledExecutorService refresher;
    private String lastRefreshFailure;

    /**
     * @param sources     read in this order, each laid over the ones before it
     * @param namesInCode profiles that code refers to by name, which a refresh may not remove
     * @param async       how listeners are called; null for on the calling thread
     * @param clock       where "now" comes from, so a test can decide what it is
     */
    public DefaultLlmProfiles(List<LlmConfigSource> sources, List<LlmVendorAdapter> adapters,
                              List<LlmCallListener> listeners, Set<String> namesInCode,
                              LlmProperties.Async async, Clock clock) {
        this.sources = List.copyOf(sources);
        this.namesInCode = Set.copyOf(namesInCode);
        this.clock = clock;
        List<String> problems = new ArrayList<>();
        this.adaptersByType = indexByType(adapters, problems);
        this.snapshot = build(load(problems), null, problems);
        this.delivery = new CallEventDelivery(listeners, async);
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
        return snapshot.names();
    }

    @Override
    public synchronized boolean refresh() {
        List<String> problems = new ArrayList<>();
        LlmSettings settings = load(problems);
        Snapshot current = snapshot;
        if (problems.isEmpty() && settings.equals(current.settings())) {
            return false;
        }
        Snapshot next = build(settings, current, problems);
        snapshot = next;
        log.info("llm configuration refreshed; profiles: " + next.names());
        return true;
    }

    /**
     * Refreshes every {@code interval} on a thread of its own until {@link #close()}. A failed
     * refresh is logged, once until it fails differently, and the configuration stays as it was.
     */
    public synchronized void refreshEvery(Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new LlmProfilesConfigurationException("llm.source.refresh-interval must be positive, got " + interval);
        }
        if (refresher != null) {
            throw new IllegalStateException("already refreshing");
        }
        refresher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "llm-config-refresh");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        refresher.scheduleWithFixedDelay(this::refreshQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void refreshQuietly() {
        try {
            refresh();
            if (lastRefreshFailure != null) {
                log.info("llm configuration can be read again");
                lastRefreshFailure = null;
            }
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.equals(lastRefreshFailure)) {
                log.warn("llm configuration not refreshed, the current one stays: " + message);
                lastRefreshFailure = message;
            }
        }
    }

    /** Stops refreshing and delivers the events still waiting for listeners. */
    @Override
    public void close() {
        synchronized (this) {
            if (refresher != null) {
                refresher.shutdownNow();
            }
        }
        delivery.close();
    }

    /** The profile as it stands now. {@code asked} is the name to report when there is none. */
    ResolvedProfile resolve(String key, String asked) {
        Snapshot current = snapshot;
        ResolvedProfile profile = key == null ? null : current.profiles().get(key);
        if (profile == null) {
            throw new UnknownProfileException(asked, current.names());
        }
        return profile;
    }

    /** The first of the models that has a price at this vendor; null when none has. */
    LlmPrice price(String vendor, String... models) {
        Map<String, LlmPrice> byModel = snapshot.prices().getOrDefault(vendor, Map.of());
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

    void publish(LlmCallEvent event) {
        delivery.publish(event);
    }

    private LlmSettings load(List<String> problems) {
        List<LlmSettings> layers = new ArrayList<>();
        for (LlmConfigSource source : sources) {
            try {
                layers.add(Objects.requireNonNullElse(source.load(), LlmSettings.EMPTY));
            } catch (RuntimeException e) {
                throw new LlmProfilesConfigurationException("could not read llm configuration from "
                        + source.getClass().getName() + ": " + e.getMessage(), e);
            }
        }
        return SettingsLayers.merge(layers, problems);
    }

    /**
     * Checks everything and connects what needs connecting. A vendor whose settings equal those it
     * had in {@code previous} keeps its connection.
     *
     * @throws LlmProfilesConfigurationException listing every problem, those passed in included
     */
    private Snapshot build(LlmSettings settings, Snapshot previous, List<String> problems) {
        Map<String, ConnectedVendor> vendors = new LinkedHashMap<>();
        settings.vendors().forEach((name, vendor) -> {
            ConnectedVendor kept = previous == null ? null : previous.vendors().get(name);
            if (kept != null && kept.settings().equals(vendor)) {
                vendors.put(name, kept);
                return;
            }
            LlmVendor connected = connect(name, vendor, problems);
            if (connected != null) {
                vendors.put(name, new ConnectedVendor(vendor, connected));
            }
        });

        Map<String, ResolvedProfile> resolved = new LinkedHashMap<>();
        settings.profiles().forEach((name, profile) -> {
            if (!StringUtils.hasText(profile.vendor())) {
                problems.add("profile '" + name + "': vendor is not set");
                return;
            }
            if (!settings.vendors().containsKey(profile.vendor())) {
                problems.add("profile '" + name + "': vendor '" + profile.vendor()
                        + "' is not declared under llm.vendors. Declared: " + settings.vendors().keySet());
                return;
            }
            ConnectedVendor vendor = vendors.get(profile.vendor());
            if (vendor == null) {
                return; // the vendor's own problem is already on the list
            }
            List<String> profileProblems = vendor.vendor().check(profile);
            if (!profileProblems.isEmpty()) {
                profileProblems.forEach(problem -> problems.add("profile '" + name + "': " + problem));
                return;
            }
            String type = vendor.settings().type().toLowerCase(Locale.ROOT);
            resolved.put(ProfileNames.canonical(name),
                    new ResolvedProfile(name, profile.vendor(), type, vendor.vendor(), profile));
        });

        Set<String> declared = new LinkedHashSet<>();
        settings.profiles().keySet().forEach(name -> declared.add(ProfileNames.canonical(name)));
        for (String name : namesInCode) {
            if (!declared.contains(ProfileNames.canonical(name))) {
                problems.add("profile '" + name + "' is named in code, so it cannot be removed while the"
                        + " application runs");
            }
        }

        Map<String, Map<String, LlmPrice>> priceList = new LinkedHashMap<>();
        settings.prices().forEach((vendor, models) -> {
            if (!settings.vendors().containsKey(vendor)) {
                problems.add("prices for vendor '" + vendor + "': no such vendor under llm.vendors. Declared: "
                        + settings.vendors().keySet());
                return;
            }
            Map<String, LlmPrice> byModel = new LinkedHashMap<>();
            models.forEach((model, price) -> {
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
        return new Snapshot(settings, Collections.unmodifiableMap(resolved),
                Collections.unmodifiableSet(new LinkedHashSet<>(settings.profiles().keySet())),
                Collections.unmodifiableMap(priceList), Collections.unmodifiableMap(vendors));
    }

    private LlmVendor connect(String name, LlmProperties.Vendor vendor, List<String> problems) {
        String type = vendor.type() == null ? null : vendor.type().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(type)) {
            problems.add("vendor '" + name + "': type is not set");
            return null;
        }
        LlmVendorAdapter adapter = adaptersByType.get(type);
        if (adapter == null) {
            problems.add("vendor '" + name + "': no adapter for type '" + type + "'. Available: "
                    + adaptersByType.keySet() + ". Vendor modules are optional dependencies,"
                    + " so the one for this type may be missing from the classpath");
            return null;
        }
        if (vendor.timeout() != null && (vendor.timeout().isNegative() || vendor.timeout().isZero())) {
            problems.add("vendor '" + name + "': timeout must be positive, got " + vendor.timeout());
            return null;
        }
        if (vendor.maxRetries() != null && vendor.maxRetries() < 0) {
            problems.add("vendor '" + name + "': max-retries cannot be negative, got " + vendor.maxRetries());
            return null;
        }
        try {
            return adapter.connect(name, vendor);
        } catch (RuntimeException e) {
            problems.add("vendor '" + name + "': " + e.getMessage());
            return null;
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

    /** Everything one configuration came to, never changed once built. */
    private record Snapshot(
            LlmSettings settings,
            Map<String, ResolvedProfile> profiles,
            Set<String> names,
            Map<String, Map<String, LlmPrice>> prices,
            Map<String, ConnectedVendor> vendors) {
    }

    /** A vendor and the settings it was connected with, to tell whether a refresh may keep it. */
    private record ConnectedVendor(LlmProperties.Vendor settings, LlmVendor vendor) {
    }
}
