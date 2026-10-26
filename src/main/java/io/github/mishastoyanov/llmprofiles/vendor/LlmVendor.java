package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmProperties;

/** One connected vendor. Every profile pointing at it shares this instance. */
public interface LlmVendor {

    /**
     * What is wrong with a profile that uses this vendor, checked at startup. Each problem is one
     * sentence without the profile's name, which the caller adds. Empty when the profile is usable.
     * <p>
     * It is the vendor's to say because what a profile needs depends on it: a model for a Spring AI
     * vendor, a path and a request template for a webhook.
     */
    List<String> check(LlmProperties.Profile profile);

    VendorReply call(VendorRequest request);
}
