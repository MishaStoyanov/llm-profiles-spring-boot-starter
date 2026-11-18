package io.github.mishastoyanov.llmprofiles;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * The last word on the options of a call to a Spring AI vendor, for what a profile cannot say:
 * a thinking budget, a seed, a provider's own parameter. Declare it as a bean; every one is
 * called, in order, on every call and stream of every profile.
 * <p>
 * The builder is the vendor's own type, with the profile's settings already on it, so a
 * customizer checks the type and sets what it needs:
 * <pre>{@code
 * @Bean
 * LlmOptionsCustomizer reviewerThinking() {
 *     return (profile, options) -> {
 *         if (profile.equals("reviewer") && options instanceof AnthropicChatOptions.Builder anthropic) {
 *             anthropic.thinkingEnabled(12_000);
 *         }
 *     };
 * }
 * }</pre>
 * Not called for a webhook, which has no options, and not for a vendor of the application's own
 * unless it calls customizers itself.
 */
@FunctionalInterface
public interface LlmOptionsCustomizer {

    /**
     * @param profile the profile's name as written in the configuration
     * @param options the options about to be sent; set on it, the returned builder is not used
     */
    void customize(String profile, ChatOptions.Builder<?> options);
}
