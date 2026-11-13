package io.github.mishastoyanov.llmprofiles;

/**
 * Where vendors, profiles and prices come from besides the application's own configuration.
 * Declare an implementation as a bean; {@code llm.source.jdbc} switches on the one that reads
 * database tables.
 * <p>
 * The application's {@code llm.*} configuration is always read first, then every source in bean
 * order. A vendor, profile or price a source returns replaces the one of the same name before it,
 * as a whole: a profile from a database that sets only the model has no temperature, whatever the
 * YAML said. A source cannot remove what came before it.
 * <p>
 * {@link #load()} is called when the registry is built and on every
 * {@link LlmProfiles#refresh() refresh}, so it should return the current state each time rather
 * than a copy taken once.
 */
@FunctionalInterface
public interface LlmConfigSource {

    /**
     * @throws RuntimeException when the source cannot be read; at startup the application then
     *         does not start, on a refresh the current configuration stays
     */
    LlmSettings load();
}
