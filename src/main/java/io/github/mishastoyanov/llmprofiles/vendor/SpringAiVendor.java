package io.github.mishastoyanov.llmprofiles.vendor;

import java.util.List;
import java.util.function.Supplier;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

/**
 * A vendor served by a Spring AI {@link ChatModel}.
 *
 * @param options a fresh builder of the vendor's own options type per call, so the call carries
 *                options the model recognises as its own rather than generic ones it has to translate
 */
public record SpringAiVendor(ChatModel chatModel, Supplier<ChatOptions.Builder<?>> options) implements LlmVendor {

    @Override
    public List<String> check(LlmProperties.Profile profile) {
        return StringUtils.hasText(profile.model()) ? List.of() : List.of("model is not set");
    }

    @Override
    public VendorReply call(VendorRequest request) {
        ChatOptions chatOptions = options.get()
                .model(request.model())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build();
        ChatResponse response = chatModel.call(new Prompt(request.prompt(), chatOptions));
        return new VendorReply(text(response), LlmUsage.from(response), model(response));
    }

    private static String text(ChatResponse response) {
        return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

    private static String model(ChatResponse response) {
        String reported = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        return StringUtils.hasText(reported) ? reported : null;
    }
}
