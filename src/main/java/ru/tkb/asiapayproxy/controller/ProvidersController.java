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
}
