package io.github.mishastoyanov.llmprofiles.vendor;

import java.time.Duration;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.anthropic.core.Timeout;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

public class AnthropicVendorAdapter implements LlmVendorAdapter {

    @Override
    public String type() {
        return "anthropic";
    }

    /**
     * Both the synchronous and the asynchronous client are given. With only one, the model builds
     * the other from ambient configuration, which has no key, and fails with "At least one
     * credential source must be specified" although a key was passed.
     * <p>
     * Spring AI calls through the first and streams through the second, so {@code timeout} bounds
     * the whole call on the first and only the wait for each piece on the second: a long answer
     * streamed steadily is not cut off.
     */
    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        if (!StringUtils.hasText(vendor.apiKey())) {
            throw new IllegalArgumentException("api-key is required for type anthropic");
        }
        AnthropicOkHttpClient.Builder sync = AnthropicOkHttpClient.builder().apiKey(vendor.apiKey());
        AnthropicOkHttpClientAsync.Builder async = AnthropicOkHttpClientAsync.builder().apiKey(vendor.apiKey());
        if (StringUtils.hasText(vendor.baseUrl())) {
            sync.baseUrl(vendor.baseUrl());
            async.baseUrl(vendor.baseUrl());
        }
        Duration timeout = vendor.timeout();
        if (timeout != null) {
            sync.timeout(timeout);
            async.timeout(Timeout.builder().connect(timeout).read(timeout).build());
        }
        if (vendor.maxRetries() != null) {
            sync.maxRetries(vendor.maxRetries());
            async.maxRetries(vendor.maxRetries());
        }
        ChatModel model = AnthropicChatModel.builder()
                .anthropicClient(sync.build())
                .anthropicClientAsync(async.build())
                .build();
        return new SpringAiVendor(model, AnthropicChatOptions::builder, timeout);
    }
}
