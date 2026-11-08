package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * One answer, or one piece of a streamed answer.
 *
 * @param text     the answer; while streaming, the text this piece adds, possibly empty
 * @param usage    {@link LlmUsage#UNKNOWN} when the vendor reported nothing, never zeros
 * @param model    the model the vendor says answered; null when it does not say, and the requested
 *                 one is reported instead
 * @param response the Spring AI response this was read from, so that a profile used as a
 *                 {@code ChatModel} hands back tool calls and metadata untouched; null for a vendor
 *                 that does not speak Spring AI
 */
public record VendorReply(String text, LlmUsage usage, String model, ChatResponse response) {

    public VendorReply(String text, LlmUsage usage, String model) {
        this(text, usage, model, null);
    }
}
