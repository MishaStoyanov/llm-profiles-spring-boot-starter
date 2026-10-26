package io.github.mishastoyanov.llmprofiles;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Tokens one call consumed. Every field is nullable and null means <em>not reported</em>, never
 * zero: a local model often bills nothing and says nothing, and counting that as zero tokens puts
 * a made-up number into whatever adds these up.
 */
public record LlmUsage(Long inputTokens, Long outputTokens, Long totalTokens) {

    public static final LlmUsage UNKNOWN = new LlmUsage(null, null, null);

    /**
     * Spring AI returns {@code EmptyUsage}, zeros rather than nulls, when a provider reports
     * nothing, so a zero is read as silence. A real call always reads at least one token, which is
     * what makes that safe.
     */
    public static LlmUsage from(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return UNKNOWN;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return UNKNOWN;
        }
        Long input = reported(usage.getPromptTokens());
        Long output = reported(usage.getCompletionTokens());
        Long total = reported(usage.getTotalTokens());
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        return new LlmUsage(input, output, total);
    }

    public boolean isUnknown() {
        return inputTokens == null && outputTokens == null && totalTokens == null;
    }

    private static Long reported(Integer value) {
        return value == null || value == 0 ? null : value.longValue();
    }
}
