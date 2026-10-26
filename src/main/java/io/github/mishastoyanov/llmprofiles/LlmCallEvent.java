package io.github.mishastoyanov.llmprofiles;

import java.time.Duration;

/**
 * One finished call, successful or not.
 *
 * @param usage {@link LlmUsage#UNKNOWN} when the call failed
 * @param error null when the call succeeded
 */
public record LlmCallEvent(
        String profile,
        String vendor,
        String model,
        Duration latency,
        LlmUsage usage,
        Throwable error) {

    public boolean failed() {
        return error != null;
    }
}
