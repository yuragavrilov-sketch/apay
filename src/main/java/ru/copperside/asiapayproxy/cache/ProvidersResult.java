package ru.copperside.asiapayproxy.cache;

public record ProvidersResult(int status, String body, CacheLookup source,
                              boolean upstreamCalled, long upstreamLatencyMs) {}
