package io.github.mishastoyanov.llmprofiles;

import java.util.Set;

/** The registry of profiles: those under {@code llm.profiles}, and those any {@link LlmConfigSource} adds. */
public interface LlmProfiles {

    /**
     * @throws UnknownProfileException when no profile has this name; the message lists the ones
     *         that exist
     */
    LlmClient profile(String name);

    /** Shorthand for {@code profile(name.name())}. */
    default LlmClient profile(LlmProfileName name) {
        return profile(name.name());
    }

    /** The profiles as they are now, by the name they were declared under. */
    Set<String> names();

    /**
     * Reads the configuration and every {@link LlmConfigSource} again and, when the result is
     * valid, switches to it at once. Calls already under way finish with what they started with;
     * clients handed out earlier, injected ones included, use the new settings from their next call.
     * A vendor whose settings did not change keeps its connection.
     *
     * @return false when nothing changed
     * @throws LlmProfilesConfigurationException when the new configuration is invalid, or drops a
     *         profile that code names (an injection point or a {@link LlmProfileName}); the current
     *         configuration then stays as it is
     */
    boolean refresh();
}
