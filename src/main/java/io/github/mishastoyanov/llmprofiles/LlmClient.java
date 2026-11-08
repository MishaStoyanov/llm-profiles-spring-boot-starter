package io.github.mishastoyanov.llmprofiles;

import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

/**
 * Calls the model a profile points at.
 * <p>
 * A client is a handle on the profile, not a copy of it: each call reads the profile's settings
 * as the registry has them at that moment.
 * <p>
 * Immutable: every {@code with*} returns a new client and leaves the profile as configured, so an
 * override in one place never leaks into another. Passing null removes the override, and the
 * profile's own value applies again.
 */
public interface LlmClient {

    /** Shorthand for {@code call(prompt).text()}. */
    String chat(String prompt);

    LlmResponse call(String prompt);

    /**
     * The answer as it is written, piece by piece. Nothing is sent until the result is
     * subscribed to, and each subscription is a call of its own.
     * <p>
     * Listeners hear of the call once, when the stream completes, fails or is cancelled, with the
     * usage the provider reported along the way.
     *
     * @throws UnsupportedOperationException when the profile's vendor does not stream, as a
     *         webhook does not
     */
    Flux<String> stream(String prompt);

    /**
     * The profile as a Spring AI {@link ChatModel}, for {@code ChatClient}, advisors and the rest
     * of Spring AI. Calls through it go through this client: the profile's settings and this
     * client's overrides apply, and listeners see them like any other call.
     * <p>
     * Options passed in with a prompt win over the profile's model, temperature and max tokens,
     * as Spring AI expects of a model's runtime options; everything else in them, tools included,
     * is passed to the vendor as it is.
     */
    ChatModel asChatModel();

    LlmClient withModel(String model);

    LlmClient withTemperature(Double temperature);

    LlmClient withMaxTokens(Integer maxTokens);

    /**
     * Puts a tag on every call this client makes, for whoever reads the record of it: who asked,
     * for which job, from which feature. Over the profile's own tags of the same key; a null value
     * removes the client's tag.
     */
    LlmClient withTag(String key, String value);

    String profile();
}
