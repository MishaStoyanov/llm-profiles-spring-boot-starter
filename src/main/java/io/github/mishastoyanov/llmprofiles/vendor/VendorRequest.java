package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * One call as the vendor sees it.
 *
 * @param settings the profile as configured
 * @param prompt   the messages, with whatever options the caller sent along; from
 *                 {@code LlmClient.call(String)} it is one user message without options
 * @param model    what the call is to use: the caller's value, else the client's override, else
 *                 the profile's; the same goes for temperature and maxTokens
 */
public record VendorRequest(
        String profile,
        LlmProperties.Profile settings,
        Prompt prompt,
        String model,
        Double temperature,
        Integer maxTokens) {

    /** The prompt as one text, for a vendor that takes no separate messages. */
    public String text() {
        return prompt.getContents();
    }
}
