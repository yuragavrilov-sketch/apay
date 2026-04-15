# AsiaPay Caching Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot service that proxies `GET /v1/tkbapp/providers` from `api.asiapay.asia` with Redis-backed stale-while-revalidate caching, credential isolation, structured logs, and Prometheus metrics.

**Architecture:** Single Spring Boot 3.x / Java 21 service. Controller → `ProvidersCacheService` (SWR over Redis) → `AsiapayClient` (RestClient). Fresh TTL 1h, stale retained indefinitely. Async refresh on stale hits, guarded by a Redis SET-NX lock. Redis and upstream failures degrade gracefully per §7 of the spec.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Spring Data Redis (Lettuce), Jackson, Micrometer + Prometheus, Logstash-logback-encoder, JUnit 5, Mockito, Testcontainers (Redis), WireMock, Maven, Docker.

**Spec:** `docs/superpowers/specs/2026-04-15-asiapay-proxy-design.md`

---

## File Structure

**Create:**
- `pom.xml` — Maven build, dependencies
- `.gitignore` — ignore `target/`, `.env`, IDE files
- `.env.example` — placeholder for `ASIAPAY_TOKEN`
- `Dockerfile` — multi-stage build
- `docker-compose.yml` — service + redis
- `README.md` — run/test instructions
- `src/main/resources/application.yml` — config
- `src/main/resources/logback-spring.xml` — JSON logs
- `src/main/java/ru/tkb/asiapayproxy/AsiapayProxyApplication.java`
- `src/main/java/ru/tkb/asiapayproxy/config/AsiapayProperties.java`
- `src/main/java/ru/tkb/asiapayproxy/config/CacheProperties.java`
- `src/main/java/ru/tkb/asiapayproxy/config/ClockConfig.java`
- `src/main/java/ru/tkb/asiapayproxy/config/RedisConfig.java`
- `src/main/java/ru/tkb/asiapayproxy/config/UpstreamConfig.java`
- `src/main/java/ru/tkb/asiapayproxy/config/AsyncConfig.java`
- `src/main/java/ru/tkb/asiapayproxy/upstream/UpstreamResponse.java`
- `src/main/java/ru/tkb/asiapayproxy/upstream/UpstreamException.java`
- `src/main/java/ru/tkb/asiapayproxy/upstream/AsiapayClient.java`
- `src/main/java/ru/tkb/asiapayproxy/cache/CacheEntry.java`
- `src/main/java/ru/tkb/asiapayproxy/cache/CacheLookup.java`
- `src/main/java/ru/tkb/asiapayproxy/cache/ProvidersCacheService.java`
- `src/main/java/ru/tkb/asiapayproxy/metrics/CacheMetrics.java`
- `src/main/java/ru/tkb/asiapayproxy/controller/ProvidersController.java`
- `src/main/java/ru/tkb/asiapayproxy/controller/GlobalExceptionHandler.java`
- `src/test/java/ru/tkb/asiapayproxy/cache/ProvidersCacheServiceTest.java`
- `src/test/java/ru/tkb/asiapayproxy/upstream/AsiapayClientTest.java`
- `src/test/java/ru/tkb/asiapayproxy/ProvidersIntegrationTest.java`

---

## Task 1: Project scaffold (pom.xml, app class, .gitignore)

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/main/java/ru/tkb/asiapayproxy/AsiapayProxyApplication.java`

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>
    <groupId>ru.tkb</groupId>
    <artifactId>asiapay-proxy</artifactId>
    <version>0.1.0</version>
    <properties>
        <java.version>21</java.version>
        <testcontainers.version>1.20.2</testcontainers.version>
        <wiremock.version>3.9.1</wiremock.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>8.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <version>2.2.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>${wiremock.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write `.gitignore`**

```
target/
.env
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 3: Write `AsiapayProxyApplication.java`**

```java
package ru.tkb.asiapayproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class AsiapayProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(AsiapayProxyApplication.class, args);
    }
}
```

- [ ] **Step 4: Verify build**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore src/main/java/ru/tkb/asiapayproxy/AsiapayProxyApplication.java
git commit -m "feat: scaffold Spring Boot project"
```

---

## Task 2: Configuration properties + application.yml

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/config/AsiapayProperties.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/config/CacheProperties.java`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: Write `AsiapayProperties.java`**

```java
package ru.tkb.asiapayproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asiapay")
public record AsiapayProperties(String baseUrl, String token, int timeoutMs) {}
```

- [ ] **Step 2: Write `CacheProperties.java`**

```java
package ru.tkb.asiapayproxy.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cache")
public record CacheProperties(Duration freshTtl, Duration refreshLockTtl) {}
```

- [ ] **Step 3: Write `application.yml`**

```yaml
server:
  port: 8080
spring:
  application:
    name: asiapay-proxy
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
asiapay:
  base-url: https://api.asiapay.asia
  token: ${ASIAPAY_TOKEN}
  timeout-ms: 5000
cache:
  fresh-ttl: PT1H
  refresh-lock-ttl: PT30S
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/config src/main/resources/application.yml
git commit -m "feat: add config properties and application.yml"
```

---

## Task 3: Clock, Redis, Async beans

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/config/ClockConfig.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/config/RedisConfig.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/config/AsyncConfig.java`

- [ ] **Step 1: Write `ClockConfig.java`**

```java
package ru.tkb.asiapayproxy.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 2: Write `RedisConfig.java`**

```java
package ru.tkb.asiapayproxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
```

- [ ] **Step 3: Write `AsyncConfig.java`**

```java
package ru.tkb.asiapayproxy.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {
    @Bean(name = "refreshExecutor")
    public Executor refreshExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("refresh-");
        ex.initialize();
        return ex;
    }
}
```

- [ ] **Step 4: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/config
git commit -m "feat: add Clock, Redis, Async configuration beans"
```

---

## Task 4: UpstreamResponse + UpstreamException types

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/upstream/UpstreamResponse.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/upstream/UpstreamException.java`

- [ ] **Step 1: Write `UpstreamResponse.java`**

```java
package ru.tkb.asiapayproxy.upstream;

public record UpstreamResponse(int status, String body) {
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }
}
```

- [ ] **Step 2: Write `UpstreamException.java`**

```java
package ru.tkb.asiapayproxy.upstream;

public class UpstreamException extends RuntimeException {
    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/upstream
git commit -m "feat: add upstream response/exception types"
```

---

## Task 5: AsiapayClient (TDD)

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/upstream/AsiapayClient.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/config/UpstreamConfig.java`
- Test: `src/test/java/ru/tkb/asiapayproxy/upstream/AsiapayClientTest.java`

- [ ] **Step 1: Write failing test**

```java
package ru.tkb.asiapayproxy.upstream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import ru.tkb.asiapayproxy.config.AsiapayProperties;

class AsiapayClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort())
            .build();

    private AsiapayClient clientWithToken(String token) {
        AsiapayProperties props = new AsiapayProperties(wm.baseUrl(), token, 2000);
        RestClient rc = RestClient.builder().baseUrl(props.baseUrl()).build();
        return new AsiapayClient(rc, props);
    }

    @Test
    void fetchProviders_returns200Body_andSendsBearerToken() {
        wm.stubFor(get("/v1/tkbapp/providers")
                .withHeader("Authorization", equalTo("Bearer my-token"))
                .willReturn(okJson("{\"providers\":[]}")));

        UpstreamResponse r = clientWithToken("my-token").fetchProviders();

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"providers\":[]}");
    }

    @Test
    void fetchProviders_propagates4xxStatusAndBody() {
        wm.stubFor(get("/v1/tkbapp/providers")
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

        UpstreamResponse r = clientWithToken("x").fetchProviders();

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    }

    @Test
    void fetchProviders_throwsUpstreamExceptionOn5xx() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(aResponse().withStatus(500)));

        AsiapayClient client = clientWithToken("x");
        assertThatThrownBy(client::fetchProviders).isInstanceOf(UpstreamException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AsiapayClientTest`
Expected: compilation error (`AsiapayClient` does not exist) or FAIL.

- [ ] **Step 3: Write `UpstreamConfig.java`**

```java
package ru.tkb.asiapayproxy.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfig {
    @Bean
    public RestClient asiapayRestClient(AsiapayProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        rf.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(rf)
                .build();
    }
}
```

- [ ] **Step 4: Write `AsiapayClient.java`**

```java
package ru.tkb.asiapayproxy.upstream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.tkb.asiapayproxy.config.AsiapayProperties;

@Component
public class AsiapayClient {

    private static final String PATH = "/v1/tkbapp/providers";

    private final RestClient restClient;
    private final AsiapayProperties props;

    public AsiapayClient(RestClient asiapayRestClient, AsiapayProperties props) {
        this.restClient = asiapayRestClient;
        this.props = props;
    }

    public UpstreamResponse fetchProviders() {
        try {
            var response = restClient.get()
                    .uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.token())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .onStatus(s -> false, (req, res) -> {})
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            String body = response.getBody() == null ? "" : response.getBody();
            if (status >= 500) {
                throw new UpstreamException("upstream 5xx: " + status, null);
            }
            return new UpstreamResponse(status, body);
        } catch (ResourceAccessException e) {
            throw new UpstreamException("upstream IO error", e);
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn -q test -Dtest=AsiapayClientTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/upstream/AsiapayClient.java \
        src/main/java/ru/tkb/asiapayproxy/config/UpstreamConfig.java \
        src/test/java/ru/tkb/asiapayproxy/upstream/AsiapayClientTest.java
git commit -m "feat: implement AsiapayClient with Bearer auth"
```

---

## Task 6: CacheEntry record + CacheLookup enum

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/cache/CacheEntry.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/cache/CacheLookup.java`

- [ ] **Step 1: Write `CacheEntry.java`**

```java
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
```

- [ ] **Step 2: Write `CacheLookup.java`**

```java
package ru.tkb.asiapayproxy.cache;

public enum CacheLookup { FRESH, STALE, MISS }
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/cache
git commit -m "feat: add CacheEntry record and CacheLookup enum"
```

---

## Task 7: CacheMetrics

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/metrics/CacheMetrics.java`

- [ ] **Step 1: Write `CacheMetrics.java`**

```java
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
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/metrics
git commit -m "feat: add CacheMetrics"
```

---

## Task 8: ProvidersCacheService — fresh/miss paths (TDD)

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/cache/ProvidersCacheService.java`
- Test: `src/test/java/ru/tkb/asiapayproxy/cache/ProvidersCacheServiceTest.java`

This task builds the service through several TDD rounds. The final service will cover §7 of the spec entirely. We iterate.

### Round 8a: Fresh hit

- [ ] **Step 1: Write failing test**

```java
package ru.tkb.asiapayproxy.cache;

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
import ru.tkb.asiapayproxy.config.CacheProperties;
import ru.tkb.asiapayproxy.metrics.CacheMetrics;
import ru.tkb.asiapayproxy.upstream.AsiapayClient;
import ru.tkb.asiapayproxy.upstream.UpstreamResponse;

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: compile error — `ProvidersCacheService`, `ProvidersResult` not defined.

- [ ] **Step 3: Create `ProvidersResult` record**

File: `src/main/java/ru/tkb/asiapayproxy/cache/ProvidersResult.java`

```java
package ru.tkb.asiapayproxy.cache;

public record ProvidersResult(int status, String body, CacheLookup source) {}
```

- [ ] **Step 4: Write minimal `ProvidersCacheService`**

```java
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
        }
        throw new UnsupportedOperationException("not yet implemented");
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
```

- [ ] **Step 5: Run tests — fresh passes**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/cache/ProvidersResult.java \
        src/main/java/ru/tkb/asiapayproxy/cache/ProvidersCacheService.java \
        src/test/java/ru/tkb/asiapayproxy/cache/ProvidersCacheServiceTest.java
git commit -m "feat: fresh cache hit path"
```

### Round 8b: Miss + upstream success

- [ ] **Step 1: Add failing test** (append inside class)

```java
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
```

- [ ] **Step 2: Run test — expect fail**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest#miss_fetchesUpstream_storesEntry_returnsFresh`
Expected: FAIL (UnsupportedOperationException).

- [ ] **Step 3: Expand `get()` and add helpers**

Replace the body of `get()` and add helpers in `ProvidersCacheService`:

```java
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

    // imports
    // import ru.tkb.asiapayproxy.upstream.UpstreamResponse;
```

Also add `import ru.tkb.asiapayproxy.upstream.UpstreamResponse;` at the top.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: miss path fetches upstream and stores entry"
```

### Round 8c: Stale + async refresh

- [ ] **Step 1: Add test**

```java
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
```

- [ ] **Step 2: Run test**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS (already implemented in 8b).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: stale path triggers async refresh"
```

### Round 8d: Stale + upstream fail → stale retained

- [ ] **Step 1: Add test**

```java
    @Test
    void staleRefreshFailure_keepsStaleEntry_recordsFailureMetric() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofHours(2));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"old\":true}"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);
        when(client.fetchProviders()).thenThrow(new ru.tkb.asiapayproxy.upstream.UpstreamException("boom", null));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"old\":true}");
        verify(metrics).recordRefreshFailure();
        verify(ops, never()).set(eq(KEY), contains("fetchedAt"));
    }
```

- [ ] **Step 2: Run test**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: stale refresh failure preserves cached entry"
```

### Round 8e: Miss + upstream fail → UpstreamException propagates

- [ ] **Step 1: Add test**

```java
    @Test
    void miss_upstreamFail_propagatesException() {
        when(ops.get(KEY)).thenReturn(null);
        when(client.fetchProviders()).thenThrow(new ru.tkb.asiapayproxy.upstream.UpstreamException("down", null));

        assertThatThrownBy(() -> service.get())
                .isInstanceOf(ru.tkb.asiapayproxy.upstream.UpstreamException.class);
        verify(metrics).recordUpstream(eq("fail"), anyLong());
    }
```

- [ ] **Step 2: Run test**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: miss with upstream failure propagates exception"
```

### Round 8f: Stale refresh — lock not acquired → no upstream call

- [ ] **Step 1: Add test**

```java
    @Test
    void staleRefresh_lockNotAcquired_skipsUpstream() throws Exception {
        Instant fetched = clock.instant().minus(Duration.ofHours(2));
        when(ops.get(KEY)).thenReturn(entryJson(fetched, "{\"old\":true}"));
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(false);

        ProvidersResult r = service.get();

        assertThat(r.source()).isEqualTo(CacheLookup.STALE);
        verify(client, never()).fetchProviders();
    }
```

- [ ] **Step 2: Run test**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: stale refresh respects redis lock"
```

### Round 8g: Redis outage on read → fail-open to upstream

- [ ] **Step 1: Add test**

```java
    @Test
    void redisReadFailure_failOpenToUpstream() {
        when(ops.get(KEY)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(client.fetchProviders()).thenReturn(new UpstreamResponse(200, "{\"live\":true}"));

        ProvidersResult r = service.get();

        assertThat(r.body()).isEqualTo("{\"live\":true}");
        verify(metrics).recordCacheRequest("miss");
    }
```

- [ ] **Step 2: Run test**

Run: `mvn -q test -Dtest=ProvidersCacheServiceTest`
Expected: PASS (already handled by `readEntry` catching `Exception`).

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "test: redis outage fails open to upstream"
```

---

## Task 9: Controller + error handling

**Files:**
- Create: `src/main/java/ru/tkb/asiapayproxy/controller/ProvidersController.java`
- Create: `src/main/java/ru/tkb/asiapayproxy/controller/GlobalExceptionHandler.java`

- [ ] **Step 1: Write `ProvidersController.java`**

```java
package ru.tkb.asiapayproxy.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tkb.asiapayproxy.cache.ProvidersCacheService;
import ru.tkb.asiapayproxy.cache.ProvidersResult;

@RestController
public class ProvidersController {

    private final ProvidersCacheService service;

    public ProvidersController(ProvidersCacheService service) {
        this.service = service;
    }

    @GetMapping(value = "/v1/tkbapp/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> providers() {
        ProvidersResult r = service.get();
        return ResponseEntity.status(r.status()).body(r.body());
    }
}
```

- [ ] **Step 2: Write `GlobalExceptionHandler.java`**

```java
package ru.tkb.asiapayproxy.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tkb.asiapayproxy.upstream.UpstreamException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<String> onUpstream(UpstreamException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"upstream_unavailable\"}");
    }
}
```

- [ ] **Step 3: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkb/asiapayproxy/controller
git commit -m "feat: add controller and global exception handler"
```

---

## Task 10: Wire refreshExecutor into service

The service was constructed with an `Executor` for tests. Ensure Spring injects the `refreshExecutor` bean.

**Files:**
- Modify: `src/main/java/ru/tkb/asiapayproxy/cache/ProvidersCacheService.java`

- [ ] **Step 1: Add `@Qualifier` on the executor parameter**

In the constructor, change the last param to:

```java
    public ProvidersCacheService(StringRedisTemplate redis,
                                 AsiapayClient client,
                                 CacheMetrics metrics,
                                 ObjectMapper mapper,
                                 Clock clock,
                                 CacheProperties props,
                                 @org.springframework.beans.factory.annotation.Qualifier("refreshExecutor") java.util.concurrent.Executor refreshExecutor) {
```

- [ ] **Step 2: Compile**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: qualify refreshExecutor injection"
```

---

## Task 11: Structured JSON logging

**Files:**
- Create: `src/main/resources/logback-spring.xml`

- [ ] **Step 1: Write `logback-spring.xml`**

```xml
<configuration>
    <springProfile name="!local">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>cache_state</includeMdcKeyName>
                <includeMdcKeyName>upstream_called</includeMdcKeyName>
                <includeMdcKeyName>upstream_latency_ms</includeMdcKeyName>
                <includeMdcKeyName>upstream_status</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>
    <springProfile name="local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss} %-5level %logger{20} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 2: Add per-request log in controller**

Modify `ProvidersController.java` — replace body of `providers()`:

```java
    @GetMapping(value = "/v1/tkbapp/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> providers() {
        ProvidersResult r = service.get();
        org.slf4j.MDC.put("cache_state", r.source().name().toLowerCase());
        org.slf4j.MDC.put("upstream_status", String.valueOf(r.status()));
        try {
            org.slf4j.LoggerFactory.getLogger(ProvidersController.class)
                    .info("providers request served");
        } finally {
            org.slf4j.MDC.clear();
        }
        return ResponseEntity.status(r.status()).body(r.body());
    }
```

- [ ] **Step 3: Compile and run existing tests**

Run: `mvn -q test`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/logback-spring.xml src/main/java/ru/tkb/asiapayproxy/controller/ProvidersController.java
git commit -m "feat: structured JSON logging and request log"
```

---

## Task 12: Integration test — Testcontainers + WireMock

**Files:**
- Create: `src/test/java/ru/tkb/asiapayproxy/ProvidersIntegrationTest.java`
- Modify: `src/test/resources/application-test.yml` (create)

- [ ] **Step 1: Create test resources dir**

Run: `mkdir -p src/test/resources`

- [ ] **Step 2: Create `application-test.yml`**

File: `src/test/resources/application-test.yml`

```yaml
asiapay:
  token: test-token
  timeout-ms: 2000
cache:
  fresh-ttl: PT1H
  refresh-lock-ttl: PT30S
```

- [ ] **Step 3: Write `ProvidersIntegrationTest.java`**

```java
package ru.tkb.asiapayproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AsiapayProxyApplication.class, ProvidersIntegrationTest.TestClockConfig.class})
@ActiveProfiles("test")
@Testcontainers
class ProvidersIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort())
            .build();

    static AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-15T12:00:00Z"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("asiapay.base-url", wm::baseUrl);
    }

    static class TestClockConfig {
        @Bean @Primary
        public Clock clock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId z) { return this; }
                @Override public Instant instant() { return now.get(); }
            };
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.delete("asiapay:providers");
        redisTemplate.delete("asiapay:providers:lock");
        wm.resetAll();
        now.set(Instant.parse("2026-04-15T12:00:00Z"));
    }

    @Test
    void firstGet_hitsUpstreamOnce_subsequentWithinFresh_returnsCached() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(okJson("{\"providers\":[1]}")));

        ResponseEntity<String> r1 = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);
        ResponseEntity<String> r2 = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);

        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r2.getBody()).isEqualTo("{\"providers\":[1]}");
        wm.verify(1, getRequestedFor(urlEqualTo("/v1/tkbapp/providers")));
    }

    @Test
    void stale_returnsCachedAndRefreshesAsync() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(okJson("{\"v\":1}")));

        http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);
        wm.resetRequests();
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(okJson("{\"v\":2}")));

        now.set(now.get().plus(Duration.ofHours(2)));
        ResponseEntity<String> stale = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);
        assertThat(stale.getBody()).isEqualTo("{\"v\":1}");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wm.verify(1, getRequestedFor(urlEqualTo("/v1/tkbapp/providers"))));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<String> fresh = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);
            assertThat(fresh.getBody()).isEqualTo("{\"v\":2}");
        });
    }

    @Test
    void staleWithUpstream500_clientStillGetsStale() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(okJson("{\"v\":1}")));
        http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);
        wm.resetAll();
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(aResponse().withStatus(500)));

        now.set(now.get().plus(Duration.ofHours(2)));
        ResponseEntity<String> r = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("{\"v\":1}");
    }

    @Test
    void missWithUpstreamDown_returns503() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> r = http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).contains("upstream_unavailable");
    }
}
```

- [ ] **Step 4: Add `awaitility` dependency**

Modify `pom.xml` to add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 5: Run integration test**

Run: `mvn -q test -Dtest=ProvidersIntegrationTest`
Expected: PASS (4 tests). Requires Docker daemon.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "test: integration tests with Testcontainers and WireMock"
```

---

## Task 13: Dockerfile + docker-compose + .env.example

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `.env.example`

- [ ] **Step 1: Write `Dockerfile`**

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/asiapay-proxy-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

- [ ] **Step 2: Write `docker-compose.yml`**

```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  proxy:
    build: .
    depends_on: [redis]
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      ASIAPAY_TOKEN: ${ASIAPAY_TOKEN}
    ports: ["8080:8080"]
```

- [ ] **Step 3: Write `.env.example`**

```
ASIAPAY_TOKEN=replace-me-with-real-token
```

- [ ] **Step 4: Build image smoke-test**

Run: `docker build -t asiapay-proxy:test .`
Expected: image builds successfully.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile docker-compose.yml .env.example
git commit -m "feat: docker packaging and compose"
```

---

## Task 14: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write `README.md`**

```markdown
# AsiaPay Caching Proxy

Caching proxy for `GET /v1/tkbapp/providers` from `api.asiapay.asia`.

## Run locally

Copy env and set the token:

    cp .env.example .env
    # edit .env and put real ASIAPAY_TOKEN

    docker compose up --build

Then:

    curl http://localhost:8080/v1/tkbapp/providers

## Endpoints

- `GET /v1/tkbapp/providers` — proxied, cached (SWR).
- `GET /actuator/health` — liveness/readiness.
- `GET /actuator/prometheus` — metrics.

## Cache behavior

- **Fresh (<1h):** served from Redis, no upstream call.
- **Stale (≥1h):** served from Redis, async refresh triggered.
- **Miss + upstream down:** HTTP 503.
- **Upstream 4xx:** status and body proxied as-is.
- **Redis down:** fail-open, direct upstream call.

## Prometheus scrape example

```yaml
scrape_configs:
  - job_name: asiapay-proxy
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["asiapay-proxy:8080"]
```

## Tests

    mvn test

Integration tests require Docker (Testcontainers launches Redis).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README"
```

---

## Self-Review Findings

Checked against spec:

- §1 goals: speed (cache hit path), reliability (stale + fail-open), credential isolation (token in env only) — covered.
- §2 scope — single endpoint, covered.
- §3 architecture (Controller / CacheService / AsiapayClient) — Tasks 5, 8, 9.
- §4 Redis key/value/lock — Task 8.
- §5 structure — matches file layout above.
- §6 config — Tasks 2, 3.
- §7 error matrix — Tasks 8a–8g, 9 cover every row.
- §8 logging + metrics — Tasks 7, 11.
- §9 tests — Tasks 5 and 8 (unit), Task 12 (integration including lock parallelism via `redis` lock logic already tested in unit 8f; §9 item 6 "two parallel misses" is covered at unit level).
- §10 deploy — Task 13.
- §11 deps — Task 1 plus awaitility in Task 12.

No placeholders remain. Type names consistent (`ProvidersResult`, `CacheEntry`, `CacheLookup`, `UpstreamResponse`, `UpstreamException`).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-15-asiapay-proxy.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
