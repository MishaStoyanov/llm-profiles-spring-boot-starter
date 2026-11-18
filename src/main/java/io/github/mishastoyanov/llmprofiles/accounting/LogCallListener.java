package io.github.mishastoyanov.llmprofiles.accounting;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * One line per call on {@value #LOGGER}: INFO for a call that succeeded, WARN for one that did not.
 * <p>
 * What is not known is written as {@code ?}, and a model without a price as {@code unpriced}, so a
 * line never shows a zero that nobody measured.
 */
public class LogCallListener implements LlmCallListener {

    public static final String LOGGER = "io.github.mishastoyanov.llmprofiles.calls";

    private final Log log;

    public LogCallListener() {
        this(LogFactory.getLog(LOGGER));
    }

    LogCallListener(Log log) {
        this.log = log;
    }

    @Override
    public void onCall(LlmCallEvent event) {
        if (event.failed()) {
            if (log.isWarnEnabled()) {
                log.warn(format(event));
            }
        } else if (log.isInfoEnabled()) {
            log.info(format(event));
        }
    }

    /** The line written for a call. */
    public static String format(LlmCallEvent event) {
        LlmUsage usage = event.usage() == null ? LlmUsage.UNKNOWN : event.usage();
        StringBuilder line = new StringBuilder()
                .append("profile=").append(event.profile())
                .append(" vendor=").append(event.vendor())
                .append(" model=").append(orUnknown(event.model()))
                .append(" latency=").append(millis(event.latency()));
        if (event.firstTokenLatency() != null) {
            line.append(" first-token=").append(millis(event.firstTokenLatency()));
        }
        line.append(" input=").append(orUnknown(usage.inputTokens()))
                .append(" output=").append(orUnknown(usage.outputTokens()));
        if (usage.reasoningTokens() != null) {
            line.append(" reasoning=").append(usage.reasoningTokens());
        }
        line                .append(" cost=").append(cost(event));
        Map<String, String> tags = event.tags();
        if (!tags.isEmpty()) {
            line.append(" tags=").append(tags);
        }
        if (event.failed()) {
            line.append(" failed=").append(event.error().getClass().getSimpleName())
                    .append(": ").append(event.error().getMessage());
        }
        return line.toString();
    }

    /** {@code 0.00032 (input 0.00012, output 0.0002)}, or {@code ? (input 0.000012, output ?)}. */
    private static String cost(LlmCallEvent event) {
        if (!event.priced()) {
            return "unpriced";
        }
        return orUnknown(plain(event.cost()))
                + " (input " + orUnknown(plain(event.inputCost()))
                + ", output " + orUnknown(plain(event.outputCost())) + ")";
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String millis(Duration duration) {
        return duration == null ? "?" : duration.toMillis() + "ms";
    }

    private static String orUnknown(Object value) {
        return value == null ? "?" : value.toString();
    }
}
