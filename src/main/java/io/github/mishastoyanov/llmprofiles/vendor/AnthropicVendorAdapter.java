package io.github.mishastoyanov.llmprofiles.vendor;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.anthropic.core.Timeout;
import com.anthropic.models.messages.OutputConfig;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.Reasoning;
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
        return new SpringAiVendor(model, AnthropicChatOptions::builder, timeout,
                UnaryOperator.identity(), UnaryOperator.identity(), REASONING);
    }

    /**
     * A level turns on adaptive thinking at that effort: the model decides how much to think,
     * within the level. Anthropic refuses a temperature while the model thinks, so a profile
     * setting both is refused at startup.
     */
    static final ReasoningOptions REASONING = new ReasoningOptions() {
        @Override
        public void apply(ChatOptions.Builder<?> options, Reasoning reasoning) {
            if (!(options instanceof AnthropicChatOptions.Builder anthropic)) {
                return;
            }
            if (reasoning == Reasoning.OFF) {
                anthropic.thinkingDisabled();
                return;
            }
            anthropic.thinkingAdaptive().effort(switch (reasoning) {
                case LOW -> OutputConfig.Effort.LOW;
                case MEDIUM -> OutputConfig.Effort.MEDIUM;
                case HIGH -> OutputConfig.Effort.HIGH;
                case MAX, OFF -> OutputConfig.Effort.MAX;
            });
        }

        @Override
        public List<String> check(LlmProperties.Profile profile) {
            return profile.reasoning() != null && profile.reasoning() != Reasoning.OFF && profile.temperature() != null
                    ? List.of("temperature cannot be set with reasoning: Anthropic allows none while the model thinks")
                    : List.of();
        }
    };
}
