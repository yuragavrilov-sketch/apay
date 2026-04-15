package ru.tkb.asiapayproxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.tkb.asiapayproxy.config.CacheProperties;
import ru.tkb.asiapayproxy.metrics.CacheMetrics;
import ru.tkb.asiapayproxy.upstream.AsiapayClient;
import ru.tkb.asiapayproxy.upstream.UpstreamResponse;

@Service
public class ProvidersCacheService {

    static final String KEY = "asiapay:providers";
    static final String LOCK = "asiapay:providers:lock";

    private final StringRedisTemplate redis;
    private final AsiapayClient client;
    private final CacheMetrics metrics;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CacheProperties props;
    private final Executor refreshExecutor;

    public ProvidersCacheService(StringRedisTemplate redis,
                                 AsiapayClient client,
                                 CacheMetrics metrics,
                                 ObjectMapper mapper,
                                 Clock clock,
                                 CacheProperties props,
                                 Executor refreshExecutor) {
        this.redis = redis;
        this.client = client;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
        this.refreshExecutor = refreshExecutor;
    }

    public ProvidersResult get() {
        CacheEntry entry = readEntry();
        if (entry != null) {
            Duration age = Duration.between(entry.fetchedAt(), clock.instant());
            if (age.compareTo(props.freshTtl()) < 0) {
                metrics.recordCacheRequest("hit");
                return new ProvidersResult(entry.upstreamStatus(), entry.body(), CacheLookup.FRESH);
            }
            metrics.recordCacheRequest("stale");
            scheduleRefresh();
            return new ProvidersResult(entry.upstreamStatus(), entry.body(), CacheLookup.STALE);
        }
        metrics.recordCacheRequest("miss");
        return fetchAndStoreSync();
    }

    private ProvidersResult fetchAndStoreSync() {
        long start = System.nanoTime();
        try {
            UpstreamResponse resp = client.fetchProviders();
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordUpstream("ok", elapsed);
            if (resp.isSuccess()) {
                storeEntry(resp);
                return new ProvidersResult(resp.status(), resp.body(), CacheLookup.MISS);
            }
            return new ProvidersResult(resp.status(), resp.body(), CacheLookup.MISS);
        } catch (ru.tkb.asiapayproxy.upstream.UpstreamException e) {
            metrics.recordUpstream("fail", (System.nanoTime() - start) / 1_000_000L);
            throw e;
        }
    }

    private void storeEntry(UpstreamResponse resp) {
        try {
            CacheEntry entry = new CacheEntry(resp.body(), clock.instant(), resp.status());
            redis.opsForValue().set(KEY, mapper.writeValueAsString(entry));
        } catch (Exception ignored) {}
    }

    private void scheduleRefresh() {
        refreshExecutor.execute(this::refreshIfLocked);
    }

    private void refreshIfLocked() {
        Boolean acquired = redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(LOCK.getBytes(), "1".getBytes(),
                        org.springframework.data.redis.core.types.Expiration.from(props.refreshLockTtl()),
                        org.springframework.data.redis.connection.RedisStringCommands.SetOption.SET_IF_ABSENT));
        if (!Boolean.TRUE.equals(acquired)) return;
        long start = System.nanoTime();
        try {
            UpstreamResponse resp = client.fetchProviders();
            metrics.recordUpstream("ok", (System.nanoTime() - start) / 1_000_000L);
            if (resp.isSuccess()) storeEntry(resp);
        } catch (ru.tkb.asiapayproxy.upstream.UpstreamException e) {
            metrics.recordUpstream("fail", (System.nanoTime() - start) / 1_000_000L);
            metrics.recordRefreshFailure();
        }
    }

    private CacheEntry readEntry() {
        try {
            String json = redis.opsForValue().get(KEY);
            if (json == null) return null;
            return mapper.readValue(json, CacheEntry.class);
        } catch (JsonProcessingException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
