package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One finished call, successful or not. A stream is one call, reported when it ends.
 * <p>
 * Everything accounting needs and nothing of the application's own: who caused a call, and for
 * which piece of work, travels in {@link #tags()}.
 *
 * @param model             the model the provider says answered, else the one asked for
 * @param startedAt         when the request was made
 * @param latency           until the whole answer was in, or the call failed
 * @param firstTokenLatency streams only: until the first piece of text arrived; null for a call
 *                          that was not streamed, or a stream that ended before any text
 * @param usage             {@link LlmUsage#UNKNOWN} when the provider said nothing; for a stream
 *                          that failed or was cancelled, whatever it had reported up to then
 * @param price             the model's price at the time of the call; null when nobody priced it,
 *                          which is not the same as free
 * @param tags              the profile's tags with the client's over them; empty when there are none
 * @param error             null when the call succeeded; a
 *                          {@link java.util.concurrent.CancellationException} when the caller
 *                          stopped listening to a stream before it ended
 */
public record LlmCallEvent(
        String profile,
        String vendor,
        String model,
        Instant startedAt,
        Duration latency,
        Duration firstTokenLatency,
        LlmUsage usage,
        LlmPrice price,
        Map<String, String> tags,
        Throwable error) {

    public LlmCallEvent {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public boolean failed() {
        return error != null;
    }

    public boolean priced() {
        return price != null;
    }

    /** Null when the model is unpriced or the provider did not report input tokens. */
    public BigDecimal inputCost() {
        return price == null ? null : price.inputCost(usage);
    }

    /** Null when the model is unpriced or the provider did not report output tokens. */
    public BigDecimal outputCost() {
        return price == null ? null : price.outputCost(usage);
    }

    /**
     * Both together; null when the model is unpriced or either part is unknown, while the part
     * that is known stays in {@link #inputCost()} or {@link #outputCost()}.
     */
    public BigDecimal cost() {
        return price == null ? null : price.cost(usage);
    }
}
