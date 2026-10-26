package io.github.mishastoyanov.llmprofiles;

import java.util.Set;

/** The registry of profiles declared under {@code llm.profiles}. */
public interface LlmProfiles {

    /**
     * @throws UnknownProfileException when no profile has this name; the message lists the ones
     *         that exist
     */
    LlmClient profile(String name);

    Set<String> names();
}
