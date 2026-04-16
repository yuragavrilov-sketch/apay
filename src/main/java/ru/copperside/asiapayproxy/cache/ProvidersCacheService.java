package ru.copperside.asiapayproxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import ru.copperside.asiapayproxy.config.CacheProperties;
import ru.copperside.asiapayproxy.metrics.CacheMetrics;
import ru.copperside.asiapayproxy.upstream.AsiapayClient;
import ru.copperside.asiapayproxy.upstream.UpstreamException;
import ru.copperside.asiapayproxy.upstream.UpstreamResponse;

@Service
public class ProvidersCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProvidersCacheService.class);

    private final String keyData;
    private final String keyLock;

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
                                 @org.springframework.beans.factory.annotation.Qualifier("refreshExecutor") java.util.concurrent.Executor refreshExecutor,
                                 Environment env) {
        this.redis = redis;
        this.client = client;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
        this.refreshExecutor = refreshExecutor;

        String[] profiles = env.getActiveProfiles();
        String profile = (profiles.length > 0) ? profiles[0] : "default";
        this.keyData = "asiapay:providers:" + profile;
        this.keyLock = "asiapay:providers:lock:" + profile;
        log.info("Redis keys: data={}, lock={}", keyData, keyLock);
    }

    public ProvidersResult get() {
        CacheEntry entry = readEntry();
        if (entry != null) {
            Duration age = Duration.between(entry.fetchedAt(), clock.instant());
            if (age.compareTo(props.freshTtl()) < 0) {
                metrics.recordCacheRequest("hit");
                return new ProvidersResult(entry.upstreamStatus(), entry.body(), CacheLookup.FRESH, false, 0);
            }
            metrics.recordCacheRequest("stale");
            scheduleRefresh();
            return new ProvidersResult(entry.upstreamStatus(), entry.body(), CacheLookup.STALE, false, 0);
        }
        metrics.recordCacheRequest("miss");
        return fetchAndStoreSync();
    }

    enum LockResult { ACQUIRED, HELD_BY_OTHER, REDIS_UNAVAILABLE }

    private LockResult tryAcquireLock() {
        try {
            Boolean acquired = redis.execute((RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(keyLock.getBytes(), "1".getBytes(),
                            Expiration.from(props.refreshLockTtl()),
                            RedisStringCommands.SetOption.SET_IF_ABSENT));
            return Boolean.TRUE.equals(acquired) ? LockResult.ACQUIRED : LockResult.HELD_BY_OTHER;
        } catch (Exception e) {
            log.warn("redis lock acquisition failed, proceeding without coordination", e);
            return LockResult.REDIS_UNAVAILABLE;
        }
    }

    private ProvidersResult fetchAndStoreSync() {
        long start = System.nanoTime();
        try {
            UpstreamResponse resp = client.fetchProviders();
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordUpstream("ok", elapsed);
            if (resp.isSuccess()) {
                storeEntry(resp);
            }
            releaseLock();
            return new ProvidersResult(resp.status(), resp.body(), CacheLookup.MISS, true, elapsed);
        } catch (UpstreamException e) {
            releaseLock();
            String outcome = e.kind() == UpstreamException.Kind.TIMEOUT ? "timeout" : "fail";
            metrics.recordUpstream(outcome, (System.nanoTime() - start) / 1_000_000L);
            throw e;
        }
    }

    private void releaseLock() {
        try {
            redis.delete(keyLock);
        } catch (Exception ignored) {}
    }

    private void storeEntry(UpstreamResponse resp) {
        try {
            CacheEntry entry = new CacheEntry(resp.body(), clock.instant(), resp.status());
            redis.opsForValue().set(keyData, mapper.writeValueAsString(entry));
        } catch (Exception e) {
            log.warn("cache store failed", e);
        }
    }

    private void scheduleRefresh() {
        try {
            refreshExecutor.execute(this::refreshIfLocked);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.debug("refresh task rejected, queue full — skipping");
        }
    }

    private void refreshIfLocked() {
        if (tryAcquireLock() != LockResult.ACQUIRED) return;
        long start = System.nanoTime();
        try {
            UpstreamResponse resp = client.fetchProviders();
            metrics.recordUpstream("ok", (System.nanoTime() - start) / 1_000_000L);
            if (resp.isSuccess()) storeEntry(resp);
        } catch (UpstreamException e) {
            String outcome = e.kind() == UpstreamException.Kind.TIMEOUT ? "timeout" : "fail";
            metrics.recordUpstream(outcome, (System.nanoTime() - start) / 1_000_000L);
            log.warn("upstream_refresh_failed", e);
            metrics.recordRefreshFailure();
        } finally {
            releaseLock();
        }
    }

    private CacheEntry readEntry() {
        try {
            String json = redis.opsForValue().get(keyData);
            if (json == null) return null;
            return mapper.readValue(json, CacheEntry.class);
        } catch (JsonProcessingException e) {
            log.warn("corrupt cache entry, deleting key={}", keyData, e);
            try { redis.delete(keyData); } catch (Exception del) { /* best effort */ }
            return null;
        } catch (Exception e) {
            log.warn("redis read failed, failing open to upstream", e);
            return null;
        }
    }
}
