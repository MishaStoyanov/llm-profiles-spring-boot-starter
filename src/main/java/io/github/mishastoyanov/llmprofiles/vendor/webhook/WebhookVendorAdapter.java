package io.github.mishastoyanov.llmprofiles.vendor.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * A workflow behind an HTTP endpoint, such as n8n, called as if it were a model. Synchronous only.
 * <p>
 * Uses the JDK's own HTTP client, so a webhook vendor costs no dependency.
 */
public class WebhookVendorAdapter implements LlmVendorAdapter {

    /** A workflow is slower than a model call; without a ceiling the JDK client would wait forever. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final JsonMapper json;

    public WebhookVendorAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String type() {
        return "webhook";
    }

    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        if (!StringUtils.hasText(vendor.baseUrl())) {
            throw new IllegalArgumentException("base-url is required for type webhook");
        }
        URI baseUrl = URI.create(vendor.baseUrl());
        LlmProperties.Auth auth = vendor.auth();
        if (auth != null && (!StringUtils.hasText(auth.header()) || !StringUtils.hasText(auth.value()))) {
            throw new IllegalArgumentException("auth needs both header and value");
        }
        if (vendor.maxRetries() != null) {
            throw new IllegalArgumentException("max-retries does not apply to a webhook: a workflow may have"
                    + " side effects, so a failed call is never repeated behind its back");
        }
        Duration timeout = vendor.timeout() != null ? vendor.timeout() : DEFAULT_TIMEOUT;
        // HTTP/1.1: over plain http the JDK client otherwise asks to upgrade to HTTP/2, and n8n
        // leaves such a request unanswered until it times out.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        return new WebhookVendor(http, baseUrl, auth, timeout, json);
    }
}
