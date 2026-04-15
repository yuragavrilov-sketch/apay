package ru.tkb.asiapayproxy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tkb.asiapayproxy.upstream.UpstreamException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<String> onUpstream(UpstreamException e) {
        log.warn("upstream_unavailable: kind={}, cause={}", e.kind(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"upstream_unavailable\"}");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> onAny(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"internal_error\"}");
    }
}
