package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import reactor.core.publisher.Flux;

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

    /** False unless the vendor overrides it together with {@link #stream}. */
    default boolean streams() {
        return false;
    }

    /**
     * The answer piece by piece. Usage may come on any piece, typically the last; a piece without
     * any reports {@link io.github.mishastoyanov.llmprofiles.LlmUsage#UNKNOWN}. Only called when
     * {@link #streams()} is true.
     */
    default Flux<VendorReply> stream(VendorRequest request) {
        return Flux.error(new UnsupportedOperationException(getClass().getName() + " does not stream"));
    }
}
