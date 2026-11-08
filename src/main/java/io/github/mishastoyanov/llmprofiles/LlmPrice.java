package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;

/**
 * What a model charged per million tokens when a call was made.
 * <p>
 * Carried by each call rather than looked up when the record is read: a price is a fact about a
 * moment, and correcting today's figure must not move last month's totals.
 */
public record LlmPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

    /** What the input tokens cost; null when the provider did not say how many there were. */
    public BigDecimal inputCost(LlmUsage usage) {
        return usage == null ? null : cost(inputPerMillion, usage.inputTokens());
    }

    /** What the output tokens cost; null when the provider did not say how many there were. */
    public BigDecimal outputCost(LlmUsage usage) {
        return usage == null ? null : cost(outputPerMillion, usage.outputTokens());
    }

    /**
     * Input and output together. Null unless both are known: half a bill is not a smaller bill,
     * it is an unknown one. The half that is known is still in {@link #inputCost} or
     * {@link #outputCost}.
     */
    public BigDecimal cost(LlmUsage usage) {
        BigDecimal input = inputCost(usage);
        BigDecimal output = outputCost(usage);
        return input == null || output == null ? null : input.add(output).stripTrailingZeros();
    }

    private static BigDecimal cost(BigDecimal perMillion, Long tokens) {
        return tokens == null ? null
                : perMillion.multiply(BigDecimal.valueOf(tokens)).movePointLeft(6).stripTrailingZeros();
    }
}
