package io.github.mishastoyanov.llmprofiles.vendor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * A vendor served by a Spring AI {@link ChatModel}.
 *
 * @param options       a fresh builder of the vendor's own options type per call, so the call
 *                      carries options the model recognises as its own rather than generic ones it
 *                      has to translate
 * @param streamTimeout how long a stream may go without a piece, the first included; null for no
 *                      limit beyond the client's own
 * @param forCall       last word on the options of a call, for what a vendor carries in its options
 *                      rather than in its client, such as a per-request timeout
 * @param forStream     the same for a stream
 * @param reasoning     how the vendor's options say a {@link io.github.mishastoyanov.llmprofiles.Reasoning}
 *                      level
 */
public record SpringAiVendor(
        ChatModel chatModel,
        Supplier<ChatOptions.Builder<?>> options,
        Duration streamTimeout,
        UnaryOperator<ChatOptions.Builder<?>> forCall,
        UnaryOperator<ChatOptions.Builder<?>> forStream,
        ReasoningOptions reasoning) implements LlmVendor {

    public SpringAiVendor(ChatModel chatModel, Supplier<ChatOptions.Builder<?>> options, Duration streamTimeout,
                          UnaryOperator<ChatOptions.Builder<?>> forCall, UnaryOperator<ChatOptions.Builder<?>> forStream) {
        this(chatModel, options, streamTimeout, forCall, forStream, ReasoningOptions.UNSUPPORTED);
    }

    public SpringAiVendor(ChatModel chatModel, Supplier<ChatOptions.Builder<?>> options, Duration streamTimeout) {
        this(chatModel, options, streamTimeout, UnaryOperator.identity(), UnaryOperator.identity());
    }

    public SpringAiVendor(ChatModel chatModel, Supplier<ChatOptions.Builder<?>> options) {
        this(chatModel, options, null);
    }

    @Override
    public List<String> check(LlmProperties.Profile profile) {
        List<String> problems = new ArrayList<>();
        if (!StringUtils.hasText(profile.model())) {
            problems.add("model is not set");
        }
        problems.addAll(reasoning.check(profile));
        return problems;
    }

    @Override
    public VendorReply call(VendorRequest request) {
        return reply(chatModel.call(prompt(request, forCall)));
    }

    @Override
    public boolean streams() {
        return true;
    }

    @Override
    public Flux<VendorReply> stream(VendorRequest request) {
        Flux<VendorReply> pieces = chatModel.stream(prompt(request, forStream)).map(SpringAiVendor::reply);
        if (streamTimeout == null) {
            return pieces;
        }
        return pieces.timeout(streamTimeout).onErrorMap(TimeoutException.class, e -> new TimeoutException(
                "no part of the answer arrived within " + streamTimeout + " (the vendor's timeout)"));
    }

    /** The defaults a caller of the profile as a {@code ChatModel} starts from. */
    public ChatOptions defaultOptions(String model, Double temperature, Integer maxTokens) {
        return forCall.apply(options.get()).model(model).temperature(temperature).maxTokens(maxTokens).build();
    }

    /**
     * Options the caller sent along are kept, tools included, and only model, temperature and
     * max tokens are set: to the caller's own when it gave them, otherwise to the profile's.
     * <p>
     * They are combined into the vendor's own options type rather than passed as they came: a
     * {@code ChatClient} sends generic options, and some models accept nothing but their own type.
     * <p>
     * The reasoning level goes in first, so that thinking set in the caller's options wins over
     * it; the application's customizers come last and have the final word.
     */
    private Prompt prompt(VendorRequest request, UnaryOperator<ChatOptions.Builder<?>> last) {
        ChatOptions given = request.prompt().getOptions();
        ChatOptions.Builder<?> builder = options.get();
        if (request.reasoning() != null) {
            reasoning.apply(builder, request.reasoning());
        }
        if (given != null) {
            builder = builder.combineWith(given.mutate());
        }
        builder = last.apply(builder)
                .model(request.model())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens());
        request.customize().accept(builder);
        return request.prompt().mutate().chatOptions(builder.build()).build();
    }

    private static VendorReply reply(ChatResponse response) {
        return new VendorReply(text(response), LlmUsage.from(response), model(response), response);
    }

    private static String text(ChatResponse response) {
        return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

    private static String model(ChatResponse response) {
        String reported = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        return StringUtils.hasText(reported) ? reported : null;
    }
}
