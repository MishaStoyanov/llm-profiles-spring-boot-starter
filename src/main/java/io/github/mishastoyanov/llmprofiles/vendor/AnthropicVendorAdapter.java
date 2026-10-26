package io.github.mishastoyanov.llmprofiles.vendor;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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
     */
    @Override
    public ChatModel chatModel(String vendorName, LlmProperties.Vendor vendor) {
        if (!StringUtils.hasText(vendor.apiKey())) {
            throw new IllegalArgumentException("api-key is required for type anthropic");
        }
        AnthropicOkHttpClient.Builder sync = AnthropicOkHttpClient.builder().apiKey(vendor.apiKey());
        AnthropicOkHttpClientAsync.Builder async = AnthropicOkHttpClientAsync.builder().apiKey(vendor.apiKey());
        if (StringUtils.hasText(vendor.baseUrl())) {
            sync.baseUrl(vendor.baseUrl());
            async.baseUrl(vendor.baseUrl());
        }
        return AnthropicChatModel.builder()
                .anthropicClient(sync.build())
                .anthropicClientAsync(async.build())
                .build();
    }

    @Override
    public ChatOptions.Builder<?> options() {
        return AnthropicChatOptions.builder();
    }
}
