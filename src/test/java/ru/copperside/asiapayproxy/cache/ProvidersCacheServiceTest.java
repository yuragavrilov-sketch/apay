package ru.copperside.asiapayproxy.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.copperside.asiapayproxy.config.CacheProperties;
import ru.copperside.asiapayproxy.metrics.CacheMetrics;
import ru.copperside.asiapayproxy.upstream.AsiapayClient;
import ru.copperside.asiapayproxy.upstream.UpstreamException;
import ru.copperside.asiapayproxy.upstream.UpstreamResponse;

class ProvidersCacheServiceTest {

    static final String KEY = "asiapay:providers";
    static final String LOCK = "asiapay:providers:lock";

    StringRedisTemplate redis;
    ValueOperations<String, String> ops;
    AsiapayClient client;
    CacheMetrics metrics;
    ObjectMapper mapper;
    Clock clock;
    ProvidersCacheService service;
    CacheProperties cacheProps;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        client = mock(AsiapayClient.class);
        metrics = mock(CacheMetrics.class);
        mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        clock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
        cacheProps = new CacheProperties(Duration.ofHours(1), Duration.ofSeconds(30));
        service = new ProvidersCacheService(redis, client, metrics, mapper, clock, cacheProps,
                Runnable::run); // sync "async" for deterministic tests
    }

    private String entryJson(Instant fetchedAt, String body) throws Exception {
        return mapper.writeValueAsString(new CacheEntry(body, fetchedAt, 200));
    }

    @Test
    void freshHit_returnsCached_withoutCallingUpstream() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofMinutes(30));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"x\":1}"));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"x\":1}");
        assertThat(r.status()).isEqualTo(200);
        verifyNoInteractions(client);
        verify(metrics).recordCacheRequest("hit");
    }

    @Test
    void miss_fetchesUpstream_storesEntry_returnsFresh() throws Exception {
        when(ops.get(KEY)).thenReturn(null);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenReturn(new UpstreamResponse(200, "{\"p\":[]}"));

        ProvidersResult r = service.get();

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"p\":[]}");
        assertThat(r.source()).isEqualTo(CacheLookup.MISS);
        verify(ops).set(eq(KEY), anyString());
        verify(metrics).recordCacheRequest("miss");
        verify(metrics).recordUpstream(eq("ok"), anyLong());
    }

    @Test
    void stale_returnsCachedImmediately_andSchedulesRefresh() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofHours(2));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"old\":true}"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenReturn(new UpstreamResponse(200, "{\"new\":true}"));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"old\":true}");
        assertThat(r.source()).isEqualTo(CacheLookup.STALE);
        verify(client).fetchProviders();
        verify(ops).set(eq(KEY), contains("{\\\"new\\\":true}"));
        verify(metrics).recordCacheRequest("stale");
    }

    @Test
    void staleRefreshFailure_keepsStaleEntry_recordsFailureMetric() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofHours(2));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"old\":true}"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenThrow(new UpstreamException(UpstreamException.Kind.IO, "boom", null));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"old\":true}");
        verify(metrics).recordRefreshFailure();
        verify(ops, never()).set(eq(KEY), contains("fetchedAt"));
    }

    @Test
    void miss_upstreamFail_propagatesException() {
        when(ops.get(KEY)).thenReturn(null);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenThrow(new UpstreamException(UpstreamException.Kind.IO, "down", null));

        assertThatThrownBy(() -> service.get())
                .isInstanceOf(UpstreamException.class);
        verify(metrics).recordUpstream(eq("fail"), anyLong());
    }

    @Test
    void staleRefresh_lockNotAcquired_skipsUpstream() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofHours(2));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"old\":true}"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(false);

        ProvidersResult r = service.get();

        assertThat(r.source()).isEqualTo(CacheLookup.STALE);
        verify(client, never()).fetchProviders();
    }

    @Test
    void redisReadFailure_failOpenToUpstream() {
        when(ops.get(KEY)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenReturn(new UpstreamResponse(200, "{\"live\":true}"));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"live\":true}");
        verify(metrics).recordCacheRequest("miss");
    }

    @Test
    void miss_fetchesUpstreamDirectly_noWait() {
        when(ops.get(KEY)).thenReturn(null);
        when(client.fetchProviders()).thenReturn(new UpstreamResponse(200, "{\"direct\":true}"));

        ProvidersResult r = service.get();

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"direct\":true}");
        assertThat(r.source()).isEqualTo(CacheLookup.MISS);
        verify(client).fetchProviders();
    }
}
