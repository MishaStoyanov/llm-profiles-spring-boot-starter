package io.github.mishastoyanov.llmprofiles;

/**
 * Sees every call made through a profile. Declare one as a bean and it is picked up.
 * <p>
 * Runs after the call returns: on the calling thread, or with {@code llm.accounting.async} on a
 * thread of the library's own. Either way calls on several threads reach it at once, so it must be
 * thread-safe. An exception thrown here is logged and does
 * not reach the caller: accounting must not be able to break the call it accounts for.
 */
@FunctionalInterface
public interface LlmCallListener {

    void onCall(LlmCallEvent event);
}
