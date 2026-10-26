package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmProperties;

/**
 * One call as the vendor sees it.
 *
 * @param settings the profile as configured
 * @param model    the profile's model, or the override; the same goes for temperature and maxTokens
 */
public record VendorRequest(
        String profile,
        LlmProperties.Profile settings,
        String prompt,
        String model,
        Double temperature,
        Integer maxTokens) {
}
