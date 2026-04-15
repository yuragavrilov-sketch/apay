package ru.tkb.asiapayproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.tkb.asiapayproxy.config.AsiapayProperties;
import ru.tkb.asiapayproxy.config.CacheProperties;

import java.util.Arrays;
import java.util.List;

@Component
public class StartupDiagnostics implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final Environment env;
    private final AsiapayProperties asiapay;
    private final CacheProperties cache;

    @Value("${spring.data.redis.host:<not-set>}")
    private String redisHost;

    @Value("${spring.data.redis.port:<not-set>}")
    private String redisPort;

    @Value("${spring.data.redis.cluster.nodes:}")
    private List<String> redisClusterNodes;

    public StartupDiagnostics(Environment env, AsiapayProperties asiapay, CacheProperties cache) {
        this.env = env;
        this.asiapay = asiapay;
        this.cache = cache;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("========== AsiaPay Proxy startup diagnostics ==========");
        log.info("active profiles       : {}", Arrays.toString(env.getActiveProfiles()));
        log.info("default profiles      : {}", Arrays.toString(env.getDefaultProfiles()));
        log.info("asiapay.base-url      : {}", asiapay.baseUrl());
        log.info("asiapay.timeout-ms    : {}", asiapay.timeoutMs());
        log.info("asiapay.token         : {}", mask(asiapay.token()));
        log.info("asiapay.token source  : {}", propertySourceOf("asiapay.token"));
        log.info("A_TOKEN source        : {}", propertySourceOf("A_TOKEN"));
        log.info("cache.fresh-ttl       : {}", cache.freshTtl());
        log.info("cache.refresh-lock-ttl: {}", cache.refreshLockTtl());
        log.info("redis standalone host : {}:{}", redisHost, redisPort);
        log.info("redis cluster nodes   : {}", redisClusterNodes.isEmpty() ? "<none — standalone>" : redisClusterNodes);
        log.info("config-server URIs    : {}", env.getProperty("spring.cloud.config.uri"));
        log.info("config-server name    : {}", env.getProperty("spring.cloud.config.name"));
        log.info("config-server enabled : {}", env.getProperty("spring.cloud.config.enabled", "true"));
        log.info("=======================================================");
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        int n = value.length();
        if (n <= 8) return "****";
        return value.substring(0, 4) + "…" + value.substring(n - 4) + " (len=" + n + ")";
    }

    private String propertySourceOf(String key) {
        if (env instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
            for (var ps : ce.getPropertySources()) {
                if (ps.containsProperty(key)) {
                    return ps.getName();
                }
            }
        }
        return "<not found>";
    }
}
