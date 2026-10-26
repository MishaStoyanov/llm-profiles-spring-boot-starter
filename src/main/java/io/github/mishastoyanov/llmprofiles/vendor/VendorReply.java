package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmUsage;

/**
 * @param usage {@link LlmUsage#UNKNOWN} when the vendor reported nothing, never zeros
 * @param model the model the vendor says answered; null when it does not say, and the requested
 *              one is reported instead
 */
public record VendorReply(String text, LlmUsage usage, String model) {
}
