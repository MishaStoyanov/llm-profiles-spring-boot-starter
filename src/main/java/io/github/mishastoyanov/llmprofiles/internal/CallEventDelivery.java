package io.github.mishastoyanov.llmprofiles.internal;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Hands every event to every listener, on the calling thread or, when {@code llm.accounting.async}
 * is on, on a thread of its own.
 * <p>
 * The async queue is bounded and never drops: when it is full the calling thread delivers the
 * event itself, which slows that call down rather than losing a record of it. Events then arrive
 * a little out of order, which a record carrying its own start time can afford. On shutdown the
 * queue is drained, for a while.
 */
final class CallEventDelivery implements AutoCloseable {

    private static final Log log = LogFactory.getLog(CallEventDelivery.class);
    private static final long DRAIN_SECONDS = 10;

    private final List<LlmCallListener> listeners;
    private final ThreadPoolExecutor executor;

    CallEventDelivery(List<LlmCallListener> listeners, LlmProperties.Async async) {
        this.listeners = List.copyOf(listeners);
        if (async == null || !async.enabled() || this.listeners.isEmpty()) {
            this.executor = null;
            return;
        }
        if (async.queueCapacity() < 1) {
            throw new IllegalArgumentException("llm.accounting.async.queue-capacity must be positive, got "
                    + async.queueCapacity());
        }
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(async.queueCapacity()),
                task -> {
                    Thread thread = new Thread(task, "llm-accounting");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    void publish(LlmCallEvent event) {
        if (executor == null || executor.isShutdown()) {
            deliver(event);
        } else {
            executor.execute(() -> deliver(event));
        }
    }

    /** Accounting must not be able to break the call it accounts for. */
    private void deliver(LlmCallEvent event) {
        for (LlmCallListener listener : listeners) {
            try {
                listener.onCall(event);
            } catch (RuntimeException e) {
                log.warn("LlmCallListener " + listener.getClass().getName() + " failed on a call to profile '"
                        + event.profile() + "'; the call itself is unaffected", e);
            }
        }
    }

    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                int left = executor.shutdownNow().size();
                log.warn(left + " LLM call events were still waiting for listeners after " + DRAIN_SECONDS
                        + "s of shutdown and were not delivered");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(executor.shutdownNow().size() + " LLM call events were not delivered: shutdown was interrupted");
        }
    }
}
