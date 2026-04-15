package ru.copperside.controlledpersonsregistry.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ResponseMessage {
    private String id;
    private String status;
    private Map<String, Object> response;
}