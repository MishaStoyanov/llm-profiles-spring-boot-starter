package io.github.mishastoyanov.llmprofiles.internal;

import java.time.Duration;
import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

/** A profile with whatever overrides have been applied on top of it. */
record DefaultLlmClient(
        String profile,
        String vendor,
        ChatModel chatModel,
        LlmVendorAdapter adapter,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmCallListener> listeners) implements LlmClient {

    private static final Log log = LogFactory.getLog(DefaultLlmClient.class);

    @Override
    public String chat(String prompt) {
        return call(prompt).text();
    }

    @Override
    public LlmResponse call(String prompt) {
        ChatOptions options = adapter.options()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        long start = System.nanoTime();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(prompt, options));
        } catch (RuntimeException e) {
            publish(new LlmCallEvent(profile, vendor, model, since(start), LlmUsage.UNKNOWN, e));
            throw e;
        }
        Duration latency = since(start);
        LlmResponse result = new LlmResponse(
                text(response), LlmUsage.from(response), latency, profile, vendor, answeredModel(response));
        publish(new LlmCallEvent(profile, vendor, result.model(), latency, result.usage(), null));
        return result;
    }

    @Override
    public LlmClient withModel(String model) {
        return new DefaultLlmClient(profile, vendor, chatModel, adapter, model, temperature, maxTokens, listeners);
    }

    @Override
    public LlmClient withTemperature(Double temperature) {
        return new DefaultLlmClient(profile, vendor, chatModel, adapter, model, temperature, maxTokens, listeners);
    }

    @Override
    public LlmClient withMaxTokens(Integer maxTokens) {
        return new DefaultLlmClient(profile, vendor, chatModel, adapter, model, temperature, maxTokens, listeners);
    }

    private static String text(ChatResponse response) {
        return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
    }

    private String answeredModel(ChatResponse response) {
        String reported = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        return StringUtils.hasText(reported) ? reported : model;
    }

    private void publish(LlmCallEvent event) {
        for (LlmCallListener listener : listeners) {
            try {
                listener.onCall(event);
            } catch (RuntimeException e) {
                log.warn("LlmCallListener " + listener.getClass().getName() + " failed on a call to profile '"
                        + profile + "'; the call itself is unaffected", e);
            }
        }
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
