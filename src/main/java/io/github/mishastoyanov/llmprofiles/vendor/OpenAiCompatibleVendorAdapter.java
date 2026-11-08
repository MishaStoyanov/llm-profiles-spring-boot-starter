package io.github.mishastoyanov.llmprofiles.vendor;

import java.time.Duration;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.Timeout;
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

    /** The OpenAI SDK's own default for a whole request. */
    private static final Duration WHOLE_STREAM = Duration.ofMinutes(10);

    @Override
    public String type() {
        return "openai-compatible";
    }

    /**
     * The SDK refuses to build without a credential, so a server that checks none still needs
     * some value here. Both clients are given, and {@code timeout} applied to each, for the
     * reasons on {@link AnthropicVendorAdapter}.
     * <p>
     * Streamed usage is asked for explicitly: the protocol leaves it out of a stream unless told.
     * <p>
     * Spring AI puts a timeout of its own on every request, 60 seconds unless the options say
     * otherwise, and it overrides the client's. A call gets {@code timeout} there; a stream gets
     * the SDK's own ten minutes as a whole, since its pieces are already bounded one by one.
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
        Duration timeout = vendor.timeout();
        if (timeout != null) {
            sync.timeout(timeout);
            async.timeout(Timeout.builder().connect(timeout).read(timeout).build());
        }
        if (vendor.maxRetries() != null) {
            sync.maxRetries(vendor.maxRetries());
            async.maxRetries(vendor.maxRetries());
        }
        ChatModel model = OpenAiChatModel.builder()
                .openAiClient(sync.build())
                .openAiClientAsync(async.build())
                .build();
        return new SpringAiVendor(model, OpenAiChatOptions::builder, timeout,
                builder -> builder instanceof OpenAiChatOptions.Builder openAi && timeout != null
                        ? openAi.timeout(timeout)
                        : builder,
                builder -> builder instanceof OpenAiChatOptions.Builder openAi
                        ? openAi.streamUsage(true).timeout(WHOLE_STREAM)
                        : builder);
    }
}
