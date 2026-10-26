package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmProperties;

/**
 * Connects one vendor {@code type}. Declare an implementation as a bean to add a type of your own;
 * a type backed by a Spring AI model can return a {@link SpringAiVendor}.
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
    LlmVendor connect(String vendorName, LlmProperties.Vendor vendor);
}
