package io.github.mishastoyanov.llmprofiles.accounting;

import java.math.BigDecimal;
import java.util.concurrent.CancellationException;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Meters for every call, tagged with {@code profile}, {@code vendor} and {@code model}:
 * <ul>
 *   <li>{@value #CALLS}: a timer, also tagged {@code outcome} ({@code success}, {@code error},
 *       {@code cancelled}) and {@code priced} ({@code true}, {@code false})</li>
 *   <li>{@value #TOKENS}: a counter, also tagged {@code type} ({@code input}, {@code output}),
 *       increased only by counts the provider reported</li>
 *   <li>{@value #COST}: a counter, also tagged {@code type} ({@code input}, {@code output}), each
 *       increased only when the model has a price and the provider reported that kind of token,
 *       so a dashboard sums what is known, and {@code priced=false} shows what is not</li>
 * </ul>
 * A call's own tags are left out: who asked is the kind of value that has no end of distinct values,
 * which a metrics backend cannot afford.
 */
public class MicrometerCallListener implements LlmCallListener {

    public static final String CALLS = "llm.calls";
    public static final String TOKENS = "llm.tokens";
    public static final String COST = "llm.cost";

    private final MeterRegistry registry;

    public MicrometerCallListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onCall(LlmCallEvent event) {
        Tags tags = Tags.of("profile", event.profile(), "vendor", event.vendor(),
                "model", event.model() == null ? "unknown" : event.model());

        Timer.builder(CALLS)
                .description("Calls made through LLM profiles")
                .tags(tags)
                .tag("outcome", outcome(event))
                .tag("priced", Boolean.toString(event.priced()))
                .register(registry)
                .record(event.latency());

        LlmUsage usage = event.usage() == null ? LlmUsage.UNKNOWN : event.usage();
        tokens(tags, "input", usage.inputTokens());
        tokens(tags, "output", usage.outputTokens());

        cost(tags, "input", event.inputCost());
        cost(tags, "output", event.outputCost());
    }

    private void cost(Tags tags, String type, BigDecimal cost) {
        if (cost != null) {
            Counter.builder(COST)
                    .description("What calls through LLM profiles cost, in the currency prices are written in")
                    .tags(tags)
                    .tag("type", type)
                    .register(registry)
                    .increment(cost.doubleValue());
        }
    }

    private void tokens(Tags tags, String type, Long count) {
        if (count != null) {
            Counter.builder(TOKENS)
                    .description("Tokens reported by providers for calls through LLM profiles")
                    .baseUnit("tokens")
                    .tags(tags)
                    .tag("type", type)
                    .register(registry)
                    .increment(count);
        }
    }

    private static String outcome(LlmCallEvent event) {
        if (!event.failed()) {
            return "success";
        }
        return event.error() instanceof CancellationException ? "cancelled" : "error";
    }
}
