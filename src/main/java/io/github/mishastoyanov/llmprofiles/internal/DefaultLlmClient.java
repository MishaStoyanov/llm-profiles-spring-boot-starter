package io.github.mishastoyanov.llmprofiles.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.vendor.VendorReply;
import io.github.mishastoyanov.llmprofiles.vendor.VendorRequest;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * A handle on a profile plus the overrides applied on top of it. The profile itself is looked up
 * in the registry on every call, never kept, so the client follows whatever the registry holds.
 *
 * @param profile the name as written in the configuration
 * @param key     the name the registry files it under
 * @param model   the override, or null for the profile's own; likewise temperature and maxTokens
 * @param tags    the client's own, laid over the profile's on each call
 */
record DefaultLlmClient(
        String profile,
        String key,
        DefaultLlmProfiles registry,
        String model,
        Double temperature,
        Integer maxTokens,
        Map<String, String> tags) implements LlmClient {

    /** What one call through the client came to. */
    record Outcome(VendorReply reply, LlmUsage usage, Duration latency, String vendor, String model) {
    }

    @Override
    public String chat(String prompt) {
        return call(prompt).text();
    }

    @Override
    public LlmResponse call(String prompt) {
        Outcome outcome = call(new Prompt(prompt));
        return new LlmResponse(outcome.reply().text(), outcome.usage(), outcome.latency(),
                profile, outcome.vendor(), outcome.model());
    }

    @Override
    public Flux<String> stream(String prompt) {
        // A piece may carry only usage and no text at all, as OpenAI's last one does.
        return stream(new Prompt(prompt)).mapNotNull(VendorReply::text).filter(StringUtils::hasLength);
    }

    @Override
    public ChatModel asChatModel() {
        return new ProfileChatModel(this);
    }

    @Override
    public LlmClient withModel(String model) {
        return new DefaultLlmClient(profile, key, registry, model, temperature, maxTokens, tags);
    }

    @Override
    public LlmClient withTemperature(Double temperature) {
        return new DefaultLlmClient(profile, key, registry, model, temperature, maxTokens, tags);
    }

    @Override
    public LlmClient withMaxTokens(Integer maxTokens) {
        return new DefaultLlmClient(profile, key, registry, model, temperature, maxTokens, tags);
    }

    @Override
    public LlmClient withTag(String key, String value) {
        Objects.requireNonNull(key, "tag key");
        Map<String, String> next = new LinkedHashMap<>(tags);
        if (value == null) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        return new DefaultLlmClient(profile, this.key, registry, model, temperature, maxTokens,
                Collections.unmodifiableMap(next));
    }

    Outcome call(Prompt prompt) {
        ResolvedProfile current = registry.resolve(key, profile);
        VendorRequest request = request(current, prompt);
        Instant startedAt = registry.now();
        long start = System.nanoTime();
        VendorReply reply;
        try {
            reply = current.vendor().call(request);
        } catch (RuntimeException e) {
            registry.publish(event(current, request.model(), request.model(), startedAt, since(start), null,
                    LlmUsage.UNKNOWN, e));
            throw e;
        }
        Duration latency = since(start);
        String answeredBy = reply.model() != null ? reply.model() : request.model();
        LlmUsage usage = reply.usage() != null ? reply.usage() : LlmUsage.UNKNOWN;
        registry.publish(event(current, answeredBy, request.model(), startedAt, latency, null, usage, null));
        return new Outcome(reply, usage, latency, current.vendorName(), answeredBy);
    }

    /**
     * Whether the profile streams is checked on the spot, so a profile that cannot fails where
     * stream() is called. Its settings are read on subscription, which is when the call is made.
     */
    Flux<VendorReply> stream(Prompt prompt) {
        requireStreaming(registry.resolve(key, profile));
        return Flux.defer(() -> {
            ResolvedProfile current = requireStreaming(registry.resolve(key, profile));
            VendorRequest request = request(current, prompt);
            StreamTally tally = new StreamTally(request.model(), registry.now());
            return current.vendor().stream(request)
                    .doOnNext(tally::add)
                    .doOnError(tally::fail)
                    .doFinally(signal -> registry.publish(tally.event(this, current, request.model(), signal)));
        });
    }

    /**
     * The price is looked up for the model that answered, then for the one asked for: a provider
     * often answers with a dated name ({@code claude-haiku-4-5-20251001}) for an undated one.
     */
    private LlmCallEvent event(ResolvedProfile current, String answeredBy, String requested, Instant startedAt,
                               Duration latency, Duration firstToken, LlmUsage usage, Throwable error) {
        Map<String, String> allTags = new LinkedHashMap<>(current.settings().tags());
        allTags.putAll(tags);
        return new LlmCallEvent(profile, current.vendorName(), answeredBy, startedAt, latency, firstToken, usage,
                registry.price(current.vendorName(), answeredBy, requested), allTags, error);
    }

    private ResolvedProfile requireStreaming(ResolvedProfile current) {
        if (!current.vendor().streams()) {
            throw new UnsupportedOperationException("profile '" + profile + "' uses vendor '" + current.vendorName()
                    + "' of type " + current.vendorType() + ", which does not stream; use call() or chat()");
        }
        return current;
    }

    /** The current profile, with this client's overrides over it and the caller's options over both. */
    ResolvedRequestValues values(ResolvedProfile current, ChatOptions given) {
        return new ResolvedRequestValues(
                first(given == null ? null : given.getModel(), model, current.settings().model()),
                first(given == null ? null : given.getTemperature(), temperature, current.settings().temperature()),
                first(given == null ? null : given.getMaxTokens(), maxTokens, current.settings().maxTokens()));
    }

    record ResolvedRequestValues(String model, Double temperature, Integer maxTokens) {
    }

    private VendorRequest request(ResolvedProfile current, Prompt prompt) {
        ResolvedRequestValues values = values(current, prompt.getOptions());
        return new VendorRequest(profile, current.settings(), prompt,
                values.model(), values.temperature(), values.maxTokens());
    }

    @SafeVarargs
    private static <T> T first(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /**
     * What a stream has reported so far. Reactor delivers one signal at a time, so plain fields do.
     * <p>
     * Providers split usage differently: Anthropic gives input tokens at the start and output at the
     * end, OpenAI and Ollama everything on the last piece, some repeat running totals. Keeping the
     * largest value of each count fits them all.
     */
    private static final class StreamTally {

        private final long start = System.nanoTime();
        private final Instant startedAt;
        private String model;
        private Duration firstToken;
        private Long input;
        private Long output;
        private Long total;
        private Throwable error;

        StreamTally(String requestedModel, Instant startedAt) {
            this.model = requestedModel;
            this.startedAt = startedAt;
        }

        void add(VendorReply piece) {
            if (firstToken == null && StringUtils.hasLength(piece.text())) {
                firstToken = since(start);
            }
            if (piece.model() != null) {
                model = piece.model();
            }
            LlmUsage usage = piece.usage();
            if (usage != null) {
                input = max(input, usage.inputTokens());
                output = max(output, usage.outputTokens());
                total = max(total, usage.totalTokens());
            }
        }

        void fail(Throwable error) {
            this.error = error;
        }

        LlmCallEvent event(DefaultLlmClient client, ResolvedProfile current, String requested, SignalType signal) {
            Throwable failure = signal == SignalType.CANCEL
                    ? new CancellationException("the stream was cancelled before it completed")
                    : error;
            // A piece's own total covers only what that piece reported, so the sum is preferred.
            Long sum = input != null && output != null ? Long.valueOf(input + output) : total;
            return client.event(current, model, requested, startedAt, since(start), firstToken,
                    new LlmUsage(input, output, sum), failure);
        }

        private static Long max(Long current, Long next) {
            if (next == null) {
                return current;
            }
            return current == null ? next : Math.max(current, next);
        }
    }
}
