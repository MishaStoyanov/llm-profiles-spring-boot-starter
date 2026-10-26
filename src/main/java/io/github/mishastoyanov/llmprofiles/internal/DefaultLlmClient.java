package io.github.mishastoyanov.llmprofiles.internal;

import java.time.Duration;
import java.util.List;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.VendorReply;
import io.github.mishastoyanov.llmprofiles.vendor.VendorRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** A profile with whatever overrides have been applied on top of it. */
record DefaultLlmClient(
        String profile,
        String vendorName,
        LlmVendor vendor,
        LlmProperties.Profile settings,
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
        VendorRequest request = new VendorRequest(profile, settings, prompt, model, temperature, maxTokens);
        long start = System.nanoTime();
        VendorReply reply;
        try {
            reply = vendor.call(request);
        } catch (RuntimeException e) {
            publish(new LlmCallEvent(profile, vendorName, model, since(start), LlmUsage.UNKNOWN, e));
            throw e;
        }
        Duration latency = since(start);
        String answeredBy = reply.model() != null ? reply.model() : model;
        LlmUsage usage = reply.usage() != null ? reply.usage() : LlmUsage.UNKNOWN;
        publish(new LlmCallEvent(profile, vendorName, answeredBy, latency, usage, null));
        return new LlmResponse(reply.text(), usage, latency, profile, vendorName, answeredBy);
    }

    @Override
    public LlmClient withModel(String model) {
        return new DefaultLlmClient(profile, vendorName, vendor, settings, model, temperature, maxTokens, listeners);
    }

    @Override
    public LlmClient withTemperature(Double temperature) {
        return new DefaultLlmClient(profile, vendorName, vendor, settings, model, temperature, maxTokens, listeners);
    }

    @Override
    public LlmClient withMaxTokens(Integer maxTokens) {
        return new DefaultLlmClient(profile, vendorName, vendor, settings, model, temperature, maxTokens, listeners);
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
