package ru.tkb.asiapayproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asiapay")
public record AsiapayProperties(String baseUrl, String token, int timeoutMs) {}
