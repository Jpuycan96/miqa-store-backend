package com.miqa.store.quote;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.function.LongSupplier;

/** Global per-process budget, NOT a per-client/IP limiter. No proxy headers are trusted. */
@Component
public class QuoteRequestRateLimit {
    private final int maximum;
    private final LongSupplier nanoTime;
    private long windowStart;
    private int used;
    @Autowired
    public QuoteRequestRateLimit(@Value("${app.quote-requests.max-requests-per-minute:120}") int maximum) {
        this(maximum, System::nanoTime);
    }
    QuoteRequestRateLimit(int maximum, LongSupplier nanoTime) {
        if (maximum < 1) throw new IllegalArgumentException("Quote request rate limit must be positive");
        this.maximum = maximum;
        this.nanoTime = nanoTime;
        windowStart = nanoTime.getAsLong();
    }
    public synchronized int retryAfterSeconds() {
        long elapsed = nanoTime.getAsLong() - windowStart;
        if (elapsed >= 60_000_000_000L) { used = 0; windowStart = nanoTime.getAsLong(); elapsed = 0; }
        if (used >= maximum) return (int) Math.max(1, (60_000_000_000L - elapsed + 999_999_999L) / 1_000_000_000L);
        used++;
        return 0;
    }
}
