package io.github.mishastoyanov.llmprofiles.internal;

import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.vendor.SpringAiVendor;
import io.github.mishastoyanov.llmprofiles.vendor.VendorReply;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A profile seen as a Spring AI model. Every call goes through the client, so a call made by a
 * {@code ChatClient} is accounted for exactly like one made through {@code LlmClient}.
 * <p>
 * A Spring AI vendor's own response is handed back untouched, tool calls and metadata included.
 * Any other vendor's answer is wrapped into a response with one generation.
 */
final class ProfileChatModel implements ChatModel {

    private final DefaultLlmClient client;

    ProfileChatModel(DefaultLlmClient client) {
        this.client = client;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        DefaultLlmClient.Outcome outcome = client.call(prompt);
        VendorReply reply = outcome.reply();
        return reply.response() != null ? reply.response() : wrap(reply.text(), outcome.model(), outcome.usage());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return client.stream(prompt).map(piece -> piece.response() != null
                ? piece.response()
                : wrap(piece.text(), piece.model(), piece.usage()));
    }

    /**
     * The profile's model, temperature and max tokens with the client's overrides, in the vendor's
     * own options type where it has one. A {@code ChatClient} starts every request from these.
     */
    @Override
    public ChatOptions getOptions() {
        ResolvedProfile current = client.registry().resolve(client.key(), client.profile());
        DefaultLlmClient.ResolvedRequestValues values = client.values(current, null);
        if (current.vendor() instanceof SpringAiVendor vendor) {
            return vendor.defaultOptions(values.model(), values.temperature(), values.maxTokens());
        }
        return ChatOptions.builder()
                .model(values.model())
                .temperature(values.temperature())
                .maxTokens(values.maxTokens())
                .build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    @Override
    public String toString() {
        return "ChatModel of LLM profile '" + client.profile() + "'";
    }

    private static ChatResponse wrap(String text, String model, LlmUsage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text == null ? "" : text))),
                ChatResponseMetadata.builder().model(model).usage(usage(usage)).build());
    }

    /** Spring AI has no "not reported", only zeros, so unknown becomes its empty usage. */
    private static Usage usage(LlmUsage usage) {
        if (usage == null || usage.isUnknown()) {
            return new EmptyUsage();
        }
        return new DefaultUsage(
                usage.inputTokens() == null ? null : Math.toIntExact(usage.inputTokens()),
                usage.outputTokens() == null ? null : Math.toIntExact(usage.outputTokens()),
                usage.totalTokens() == null ? null : Math.toIntExact(usage.totalTokens()));
    }
}
