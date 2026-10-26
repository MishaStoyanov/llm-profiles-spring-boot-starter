package io.github.mishastoyanov.llmprofiles;

import java.time.Duration;

/**
 * One answer and what it took to get it.
 *
 * @param model the model the provider says answered, which can be more specific than the one
 *              asked for ({@code claude-haiku-4-5} comes back dated); the requested one when the
 *              provider does not say
 */
public record LlmResponse(
        String text,
        LlmUsage usage,
        Duration latency,
        String profile,
        String vendor,
        String model) {
}
