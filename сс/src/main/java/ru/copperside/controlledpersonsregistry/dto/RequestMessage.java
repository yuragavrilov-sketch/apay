package ru.copperside.controlledpersonsregistry.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class RequestMessage {
    private String id;

    private String extId;

    /**
     * Payload body, любые неизвестные поля будут автоматически складываться сюда.
     */
    private Map<String, Object> body = new HashMap<>();

    @JsonAnySetter
    public void putUnknownFieldToBody(String key, Object value) {
        if (body == null) {
            body = new HashMap<>();
        }
        body.put(key, value);
    }
}
