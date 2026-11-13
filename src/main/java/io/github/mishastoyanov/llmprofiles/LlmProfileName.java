package io.github.mishastoyanov.llmprofiles;

/**
 * A profile name the compiler knows about. Meant for an enum, whose {@code name()} implements it
 * with no code at all:
 * <pre>{@code
 * enum Profiles implements LlmProfileName { SUMMARIZER, LOCAL_DRAFT }
 *
 * llm.profile(Profiles.LOCAL_DRAFT).chat(prompt);   // profile local-draft
 * }</pre>
 * Case, dashes and underscores are ignored as everywhere else, so {@code LOCAL_DRAFT} is
 * {@code local-draft}. Enums implementing this interface in the application's packages (those of
 * its {@code @SpringBootApplication}) are found at startup, and a constant without a profile under
 * {@code llm.profiles} stops the application the way a misnamed injection point does.
 */
public interface LlmProfileName {

    String name();
}
