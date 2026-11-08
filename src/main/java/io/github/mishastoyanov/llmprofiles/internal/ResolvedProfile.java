package io.github.mishastoyanov.llmprofiles.internal;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;

/**
 * A profile as the registry currently has it: its settings and the vendor they point at.
 *
 * @param name       as written in the configuration
 * @param vendorName the key under {@code llm.vendors}
 * @param vendorType the vendor's {@code type}, in lower case
 */
record ResolvedProfile(
        String name,
        String vendorName,
        String vendorType,
        LlmVendor vendor,
        LlmProperties.Profile settings) {
}
