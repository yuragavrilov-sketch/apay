package ru.copperside.asiapayproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import redis.embedded.RedisServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AsiapayProxyApplication.class, ProvidersIntegrationTest.TestClockConfig.class},
        properties = {"spring.main.allow-bean-definition-overriding=true"})
@ActiveProfiles("local")
class ProvidersIntegrationTest {

    static RedisServer redisServer;
    static int redisPort;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort())
            .build();

    static AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-15T12:00:00Z"));

    @BeforeAll
    static void startRedis() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            redisPort = s.getLocalPort();
        }
        redisServer = RedisServer.newRedisServer().port(redisPort).setting("maxmemory 64M").build();
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redisServer != null) redisServer.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", () -> "localhost");
        r.add("spring.data.redis.port", () -> redisPort);
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
        redisTemplate.delete("asiapay:providers:local");
        redisTemplate.delete("asiapay:providers:lock:local");
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

    @Test
    void parallelMisses_allReturn200() throws Exception {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(okJson("{\"v\":\"parallel\"}").withFixedDelay(200)));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.List<java.util.concurrent.Future<ResponseEntity<String>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() ->
                    http.getForEntity("http://localhost:" + port + "/v1/tkbapp/providers", String.class)));
        }
        for (var f : futures) {
            ResponseEntity<String> r = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getBody()).isEqualTo("{\"v\":\"parallel\"}");
        }
        pool.shutdown();
    }
}
