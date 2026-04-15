package ru.tkb.asiapayproxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class CacheMetrics {

    private final MeterRegistry registry;
    private final Counter refreshFailures;

    public CacheMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.refreshFailures = Counter.builder("cache_refresh_failures_total").register(registry);
    }

    public void recordCacheRequest(String state) {
        Counter.builder("cache_requests_total").tag("state", state).register(registry).increment();
    }

    public void recordUpstream(String outcome, long latencyMs) {
        Counter.builder("upstream_requests_total").tag("outcome", outcome).register(registry).increment();
        Timer.builder("upstream_latency_seconds").register(registry)
                .record(java.time.Duration.ofMillis(latencyMs));
    }

    public void recordRefreshFailure() {
        refreshFailures.increment();
    }
}
