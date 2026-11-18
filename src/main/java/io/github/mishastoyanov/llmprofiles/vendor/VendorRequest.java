package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.function.Consumer;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.Reasoning;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * One call as the vendor sees it.
 *
 * @param settings the profile as configured
 * @param prompt   the messages, with whatever options the caller sent along; from
 *                 {@code LlmClient.call(String)} it is one user message without options
 * @param model    what the call is to use: the caller's value, else the client's override, else
 *                 the profile's; the same goes for temperature, maxTokens and reasoning
 * @param customize  the application's {@link io.github.mishastoyanov.llmprofiles.LlmOptionsCustomizer
 *                   customizers}, for a vendor with Spring AI options to apply last
 */
public record VendorRequest(
        String profile,
        LlmProperties.Profile settings,
        Prompt prompt,
        String model,
        Double temperature,
        Integer maxTokens,
        Reasoning reasoning,
        Consumer<ChatOptions.Builder<?>> customize) {

    public VendorRequest {
        if (customize == null) {
            customize = options -> {
            };
        }
    }

    /** The prompt as one text, for a vendor that takes no separate messages. */
    public String text() {
        return prompt.getContents();
    }
}
