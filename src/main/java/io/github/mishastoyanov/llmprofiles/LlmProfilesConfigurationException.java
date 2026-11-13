package io.github.mishastoyanov.llmprofiles;

import java.util.List;

/** The {@code llm.*} configuration, or the code that uses it, does not hold together. */
public class LlmProfilesConfigurationException extends IllegalStateException {

    public LlmProfilesConfigurationException(String message) {
        super(message);
    }

    public LlmProfilesConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** All problems at once, so fixing the configuration takes one restart rather than one per mistake. */
    public static LlmProfilesConfigurationException of(String heading, List<String> problems) {
        StringBuilder message = new StringBuilder(heading);
        problems.forEach(problem -> message.append(System.lineSeparator()).append("  - ").append(problem));
        return new LlmProfilesConfigurationException(message.toString());
    }
}
