package io.github.mishastoyanov.llmprofiles;

/**
 * Calls the model a profile points at. Immutable: every {@code with*} returns a new client and
 * leaves the profile as configured, so an override in one place never leaks into another.
 */
public interface LlmClient {

    /** Shorthand for {@code call(prompt).text()}. */
    String chat(String prompt);

    LlmResponse call(String prompt);

    LlmClient withModel(String model);

    LlmClient withTemperature(Double temperature);

    LlmClient withMaxTokens(Integer maxTokens);

    String profile();
}
