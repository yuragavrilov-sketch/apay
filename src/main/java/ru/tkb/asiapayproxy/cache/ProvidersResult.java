package ru.tkb.asiapayproxy.cache;

public record ProvidersResult(int status, String body, CacheLookup source,
                              boolean upstreamCalled, long upstreamLatencyMs) {}
