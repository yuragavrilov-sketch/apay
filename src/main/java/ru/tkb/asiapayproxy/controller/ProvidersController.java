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
