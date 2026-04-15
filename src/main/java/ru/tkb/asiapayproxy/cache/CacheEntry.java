package ru.tkb.asiapayproxy.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record CacheEntry(String body, Instant fetchedAt, int upstreamStatus) {
    @JsonCreator
    public CacheEntry(
            @JsonProperty("body") String body,
            @JsonProperty("fetchedAt") Instant fetchedAt,
            @JsonProperty("upstreamStatus") int upstreamStatus) {
        this.body = body;
        this.fetchedAt = fetchedAt;
        this.upstreamStatus = upstreamStatus;
    }
}
