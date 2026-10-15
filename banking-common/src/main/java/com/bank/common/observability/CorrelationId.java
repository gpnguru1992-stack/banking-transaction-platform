package com.bank.common.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String currentOrNew() {
        String current = current();
        return current != null ? current : UUID.randomUUID().toString();
    }

    /** Accepts a caller-supplied id only if it is safe to log; otherwise generates one. */
    public static String sanitizeOrNew(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }

    /** Runs {@code action} with the given correlation id in the MDC (used by Kafka consumers). */
    public static void runWith(String correlationId, Runnable action) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, sanitizeOrNew(correlationId));
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
