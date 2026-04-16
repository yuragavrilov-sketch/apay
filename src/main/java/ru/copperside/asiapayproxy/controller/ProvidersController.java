package ru.copperside.asiapayproxy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.copperside.asiapayproxy.cache.ProvidersCacheService;
import ru.copperside.asiapayproxy.cache.ProvidersResult;

@RestController
public class ProvidersController {

    private static final Logger log = LoggerFactory.getLogger(ProvidersController.class);

    private final ProvidersCacheService service;

    public ProvidersController(ProvidersCacheService service) {
        this.service = service;
    }

    @GetMapping(value = "/v1/tkbapp/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> providers() {
        ProvidersResult r = service.get();
        MDC.put("cache_state", r.source().name().toLowerCase());
        MDC.put("upstream_status", String.valueOf(r.status()));
        MDC.put("upstream_called", String.valueOf(r.upstreamCalled()));
        MDC.put("upstream_latency_ms", String.valueOf(r.upstreamLatencyMs()));
        try {
            log.info("providers request served");
        } finally {
            MDC.clear();
        }
        return ResponseEntity.status(r.status()).body(r.body());
    }
}
