package io.github.mishastoyanov.llmprofiles.vendor;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.util.StringUtils;

/** A local Ollama server; {@code base-url} defaults to {@code http://localhost:11434}. */
public class OllamaVendorAdapter implements LlmVendorAdapter {

    @Override
    public String type() {
        return "ollama";
    }

    @Override
    public LlmVendor connect(String vendorName, LlmProperties.Vendor vendor) {
        OllamaApi.Builder api = OllamaApi.builder();
        if (StringUtils.hasText(vendor.baseUrl())) {
            api.baseUrl(vendor.baseUrl());
        }
        return new SpringAiVendor(OllamaChatModel.builder().ollamaApi(api.build()).build(), OllamaChatOptions::builder);
    }
}
