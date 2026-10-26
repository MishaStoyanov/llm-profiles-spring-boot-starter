package io.github.mishastoyanov.llmprofiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** Vendor type {@code fake}: answers without a network and remembers what it was asked. */
class FakeVendor implements LlmVendorAdapter {

    final List<Prompt> prompts = new ArrayList<>();

    /** Replaced by a test that needs a different answer, or a failure. */
    Function<Prompt, ChatResponse> answer = prompt -> response(
            "echo: " + prompt.getContents(), prompt.getOptions().getModel() + "-2026", new DefaultUsage(12, 3));

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public ChatModel chatModel(String vendorName, LlmProperties.Vendor vendor) {
        return prompt -> {
            prompts.add(prompt);
            return answer.apply(prompt);
        };
    }

    @Override
    public ChatOptions.Builder<?> options() {
        return ChatOptions.builder();
    }

    Prompt lastPrompt() {
        return prompts.getLast();
    }

    static ChatResponse response(String text, String model, org.springframework.ai.chat.metadata.Usage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).usage(usage).build());
    }
}
