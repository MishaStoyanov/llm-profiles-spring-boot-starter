package io.github.mishastoyanov.llmprofiles.autoconfigure;

import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.internal.DefaultLlmProfiles;
import io.github.mishastoyanov.llmprofiles.internal.LlmProfileInjection;
import io.github.mishastoyanov.llmprofiles.vendor.AnthropicVendorAdapter;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendorAdapter;
import io.github.mishastoyanov.llmprofiles.vendor.OllamaVendorAdapter;
import io.github.mishastoyanov.llmprofiles.vendor.OpenAiCompatibleVendorAdapter;
import io.github.mishastoyanov.llmprofiles.vendor.webhook.WebhookVendorAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmProfilesAutoConfiguration {

    /** Static: a post-processor declared by an instance method would force this class to be built first. */
    @Bean
    static LlmProfileInjection llmProfileInjection() {
        return new LlmProfileInjection();
    }

    @Bean
    @ConditionalOnMissingBean
    LlmProfiles llmProfiles(LlmProperties properties,
                            ObjectProvider<LlmVendorAdapter> adapters,
                            ObjectProvider<LlmCallListener> listeners) {
        return new DefaultLlmProfiles(properties, adapters.orderedStream().toList(), listeners.orderedStream().toList());
    }

    // Conditions name classes as strings: a class literal for a vendor that is not on the
    // classpath would fail to load this configuration instead of switching the adapter off.

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.ai.anthropic.AnthropicChatModel",
            "com.anthropic.client.okhttp.AnthropicOkHttpClient"})
    static class Anthropic {

        @Bean
        AnthropicVendorAdapter anthropicVendorAdapter() {
            return new AnthropicVendorAdapter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.ai.openai.OpenAiChatModel",
            "com.openai.client.okhttp.OpenAIOkHttpClient"})
    static class OpenAiCompatible {

        @Bean
        OpenAiCompatibleVendorAdapter openAiCompatibleVendorAdapter() {
            return new OpenAiCompatibleVendorAdapter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.ai.ollama.OllamaChatModel")
    static class Ollama {

        @Bean
        OllamaVendorAdapter ollamaVendorAdapter() {
            return new OllamaVendorAdapter();
        }
    }

    /**
     * Always on. Its own mapper, not the application's: a naming strategy or a lenient parser set up
     * for the application's API would silently change what a request template renders to.
     */
    @Bean
    WebhookVendorAdapter webhookVendorAdapter() {
        return new WebhookVendorAdapter(JsonMapper.shared());
    }
}
