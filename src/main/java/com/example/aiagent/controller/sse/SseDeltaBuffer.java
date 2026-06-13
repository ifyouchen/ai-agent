package com.example.aiagent.controller.sse;

import java.util.function.Function;

/**
 * Small synchronous buffer for high-frequency SSE text deltas.
 */
public class SseDeltaBuffer {

    @FunctionalInterface
    public interface Sender {
        boolean send(String eventName, String data);
    }

    private final String eventName;
    private final int minChars;
    private final long flushIntervalNanos;
    private final Function<String, String> payloadMapper;
    private final Sender sender;
    private final StringBuilder buffer = new StringBuilder();
    private long lastFlushNanos = System.nanoTime();

    public SseDeltaBuffer(String eventName,
                          int minChars,
                          long flushIntervalMs,
                          Function<String, String> payloadMapper,
                          Sender sender) {
        this.eventName = eventName;
        this.minChars = Math.max(minChars, 1);
        this.flushIntervalNanos = Math.max(flushIntervalMs, 1L) * 1_000_000L;
        this.payloadMapper = payloadMapper != null ? payloadMapper : Function.identity();
        this.sender = sender;
    }

    public synchronized boolean append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return true;
        }
        buffer.append(delta);
        long now = System.nanoTime();
        if (buffer.length() >= minChars || now - lastFlushNanos >= flushIntervalNanos) {
            return flush(now);
        }
        return true;
    }

    public synchronized boolean flush() {
        return flush(System.nanoTime());
    }

    private boolean flush(long now) {
        if (buffer.isEmpty()) {
            lastFlushNanos = now;
            return true;
        }
        String payload = payloadMapper.apply(buffer.toString());
        buffer.setLength(0);
        lastFlushNanos = now;
        return sender.send(eventName, payload);
    }
}
