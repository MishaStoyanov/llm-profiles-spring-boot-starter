package io.github.mishastoyanov.llmprofiles.vendor;

import java.net.http.HttpClient;
import java.time.Duration;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** A local Ollama server; {@code base-url} defaults to {@code http://localhost:11434}. */
public class OllamaVendorAdapter implements LlmVendorAdapter {

    @Override
    public String type() {
        return "ollama";
    }

    /**
     * With a {@code timeout} both clients run on the JDK's HTTP client, which waits that long for
     * the answer to start. Ollama sends nothing until a non-streamed answer is complete, so for a
     * call that is the whole call; for a stream the wait between pieces is bounded separately.
     */
    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        OllamaApi.Builder api = OllamaApi.builder();
        if (StringUtils.hasText(vendor.baseUrl())) {
            api.baseUrl(vendor.baseUrl());
        }
        Duration timeout = vendor.timeout();
        if (timeout != null) {
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
            requests.setReadTimeout(timeout);
            JdkClientHttpConnector connector = new JdkClientHttpConnector(http);
            connector.setReadTimeout(timeout);
            api.restClientBuilder(RestClient.builder().requestFactory(requests))
                    .webClientBuilder(WebClient.builder().clientConnector(connector));
        }
        OllamaChatModel.Builder model = OllamaChatModel.builder().ollamaApi(api.build());
        if (vendor.maxRetries() != null) {
            model.retryTemplate(retries(vendor.maxRetries()));
        }
        return new SpringAiVendor(model.build(), OllamaChatOptions::builder, timeout);
    }

    /** Spring AI's own default policy, with the number of retries changed and nothing else. */
    private static RetryTemplate retries(int maxRetries) {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(maxRetries)
                .includes(TransientAiException.class, ResourceAccessException.class)
                .delay(Duration.ofSeconds(2))
                .multiplier(5)
                .maxDelay(Duration.ofMinutes(3))
                .build());
    }
}
