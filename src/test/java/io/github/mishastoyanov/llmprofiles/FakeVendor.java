package io.github.mishastoyanov.llmprofiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import io.github.mishastoyanov.llmprofiles.vendor.SpringAiVendor;
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

/** Vendor type {@code fake}: a Spring AI model that answers without a network and remembers what it was asked. */
class FakeVendor implements LlmVendorAdapter {

    final List<Prompt> prompts = new ArrayList<>();

    /** Replaced by a test that needs a different answer, or a failure. */
    Function<Prompt, ChatResponse> answer = prompt -> response(
            "echo: " + prompt.getContents(), prompt.getOptions().getModel() + "-2026", new DefaultUsage(12, 3));

    /** Split the way Anthropic splits it: input tokens on the first piece, output on the last. */
    Function<Prompt, Flux<ChatResponse>> streamed = prompt -> Flux.just(
            response("echo", prompt.getOptions().getModel() + "-2026", new DefaultUsage(12, 0)),
            response(": ", null, new EmptyUsage()),
            response(prompt.getContents(), null, new DefaultUsage(0, 3)));

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        return new SpringAiVendor(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return answer.apply(prompt);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                return streamed.apply(prompt);
            }
        }, ChatOptions::builder, vendor.timeout());
    }

    Prompt lastPrompt() {
        return prompts.getLast();
    }

    static ChatResponse response(String text, String model, Usage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).usage(usage).build());
    }
}
