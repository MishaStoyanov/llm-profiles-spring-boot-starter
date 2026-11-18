package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.Reasoning;
import org.springframework.ai.chat.prompt.ChatOptions;

/** How a vendor type turns a {@link Reasoning} level into its own options. */
public interface ReasoningOptions {

    /** For a vendor type that has no such setting: a profile asking for one is refused at startup. */
    ReasoningOptions UNSUPPORTED = new ReasoningOptions() {
        @Override
        public void apply(ChatOptions.Builder<?> options, Reasoning reasoning) {
            throw new UnsupportedOperationException("this vendor type has no reasoning setting");
        }

        @Override
        public List<String> check(LlmProperties.Profile profile) {
            return profile.reasoning() == null ? List.of()
                    : List.of("reasoning is set, but this vendor type has no reasoning setting");
        }
    };

    /** Sets the level on the vendor's own builder, the one its options supplier made. */
    void apply(ChatOptions.Builder<?> options, Reasoning reasoning);

    /** What the vendor cannot combine with the profile's level; see {@link LlmVendor#check}. */
    default List<String> check(LlmProperties.Profile profile) {
        return List.of();
    }
}
