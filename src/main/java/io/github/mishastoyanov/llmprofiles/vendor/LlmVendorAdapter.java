package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Connects one vendor {@code type} to Spring AI. Declare an implementation as a bean to add a type
 * of your own.
 */
public interface LlmVendorAdapter {

    /** The value of {@code llm.vendors.<name>.type} this adapter serves, in lower case. */
    String type();

    /**
     * Called once per declared vendor at startup.
     *
     * @throws IllegalArgumentException when the vendor's settings are incomplete; the message is
     *         shown to whoever wrote the configuration
     */
    ChatModel chatModel(String vendorName, LlmProperties.Vendor vendor);

    /**
     * A fresh builder for per-call options. It is the vendor's own options type, so the call
     * carries options the model recognises as its own rather than generic ones it has to translate.
     */
    ChatOptions.Builder<?> options();
}
