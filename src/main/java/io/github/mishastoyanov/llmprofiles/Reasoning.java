package io.github.mishastoyanov.llmprofiles;

/**
 * How much a model is to think before it answers, said once for every vendor. Each vendor type
 * turns it into its own setting; what a level costs and buys is the model's.
 * <p>
 * Written in lower case in YAML: {@code reasoning: off}.
 * <ul>
 *   <li>{@code anthropic}: off disables thinking; the levels turn on adaptive thinking with that
 *       effort, {@code max} included.</li>
 *   <li>{@code openai-compatible}: {@code reasoning_effort}; off is {@code none}, max is
 *       {@code high}. Which values a server accepts depends on the model.</li>
 *   <li>{@code ollama}: off is {@code think: false}; the levels are {@code think} levels, max is
 *       {@code high}. A model that cannot think refuses any level.</li>
 *   <li>{@code webhook}: the {@code {{reasoning}}} placeholder, in lower case.</li>
 * </ul>
 */
public enum Reasoning {

    OFF, LOW, MEDIUM, HIGH, MAX;

    /** As written in the configuration, and as a webhook receives it. */
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
