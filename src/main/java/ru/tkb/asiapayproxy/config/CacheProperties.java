package ru.tkb.asiapayproxy.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cache")
public record CacheProperties(Duration freshTtl, Duration refreshLockTtl) {}
