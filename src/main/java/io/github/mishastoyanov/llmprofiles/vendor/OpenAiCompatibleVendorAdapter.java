package io.github.mishastoyanov.llmprofiles.vendor;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;

/**
 * Anything that speaks the OpenAI wire protocol: OpenAI itself when {@code base-url} is left out,
 * otherwise vLLM, DeepSeek, Gemini's compatibility endpoint and the like.
 */
public class OpenAiCompatibleVendorAdapter implements LlmVendorAdapter {

    @Override
    public String type() {
        return "openai-compatible";
    }

    /**
     * The SDK refuses to build without a credential, so a server that checks none still needs
     * some value here. Both clients are given for the reason on {@link AnthropicVendorAdapter}.
     */
    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        if (!StringUtils.hasText(vendor.apiKey())) {
            throw new IllegalArgumentException("api-key is required for type openai-compatible"
                    + " (any non-empty value for a server that does not check it)");
        }
        OpenAIOkHttpClient.Builder sync = OpenAIOkHttpClient.builder().apiKey(vendor.apiKey());
        OpenAIOkHttpClientAsync.Builder async = OpenAIOkHttpClientAsync.builder().apiKey(vendor.apiKey());
        if (StringUtils.hasText(vendor.baseUrl())) {
            sync.baseUrl(vendor.baseUrl());
            async.baseUrl(vendor.baseUrl());
        }
        ChatModel model = OpenAiChatModel.builder()
                .openAiClient(sync.build())
                .openAiClientAsync(async.build())
                .build();
        return new SpringAiVendor(model, OpenAiChatOptions::builder);
    }
}
