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
