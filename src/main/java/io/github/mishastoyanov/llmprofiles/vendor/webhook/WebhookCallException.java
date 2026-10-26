package io.github.mishastoyanov.llmprofiles.vendor.webhook;

/** The webhook could not be reached, answered with an error, or answered with something unreadable. */
public class WebhookCallException extends RuntimeException {

    WebhookCallException(String message) {
        super(message);
    }

    WebhookCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
