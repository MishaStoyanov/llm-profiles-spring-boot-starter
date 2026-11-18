package io.github.mishastoyanov.llmprofiles.vendor;

import java.time.Duration;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.Timeout;
import com.openai.models.completions.CompletionUsage;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
     * Stream options are left unset: Spring AI then asks for usage alone. Set explicitly, they also
     * carry {@code include_obfuscation}, which servers other than OpenAI (Gemini) reject.
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
                        ? openAi.timeout(WHOLE_STREAM)
                        : builder,
                REASONING, OpenAiCompatibleVendorAdapter::usage);
    }

    /**
     * OpenAI counts thinking inside {@code completion_tokens} and says how much in
     * {@code reasoning_tokens}. Gemini leaves it out of {@code completion_tokens} and says nothing
     * of it, so the only trace is a total larger than input and output together; that difference
     * is taken as thinking and added to the output, which is what it is billed as.
     */
    static LlmUsage usage(ChatResponse response) {
        LlmUsage usage = LlmUsage.from(response);
        Object reported = response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getNativeUsage();
        if (reported instanceof CompletionUsage completion) {
            Long reasoning = completion.completionTokensDetails()
                    .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                    .filter(tokens -> tokens > 0)
                    .orElse(null);
            if (reasoning != null) {
                return usage.withReasoningTokens(reasoning);
            }
        }
        Long input = usage.inputTokens();
        Long output = usage.outputTokens();
        Long total = usage.totalTokens();
        if (input != null && output != null && total != null && total > input + output) {
            long unaccounted = total - input - output;
            return new LlmUsage(input, output + unaccounted, total, unaccounted);
        }
        return usage;
    }

    /**
     * {@code reasoning_effort}. Off is {@code none} and max is {@code high}: what a server accepts
     * beyond low, medium and high differs from model to model.
     */
    static final ReasoningOptions REASONING = (options, reasoning) -> {
        if (options instanceof OpenAiChatOptions.Builder openAi) {
            openAi.reasoningEffort(switch (reasoning) {
                case OFF -> "none";
                case LOW -> "low";
                case MEDIUM -> "medium";
                case HIGH, MAX -> "high";
            });
        }
    };
}
