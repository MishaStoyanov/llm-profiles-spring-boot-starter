package io.github.mishastoyanov.llmprofiles;

/**
 * Sees every call made through a profile. Declare one as a bean and it is picked up.
 * <p>
 * Runs on the calling thread after the call returns. An exception thrown here is logged and does
 * not reach the caller: accounting must not be able to break the call it accounts for.
 */
@FunctionalInterface
public interface LlmCallListener {

    void onCall(LlmCallEvent event);
}
