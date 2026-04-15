package ru.copperside.controlledpersonsregistry.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.copperside.controlledpersonsregistry.dto.RequestMessage;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "controlled-persons-registry.request-processor", name = "type", havingValue = "stub", matchIfMissing = true)
public class StubRequestProcessor implements RequestProcessor {
    @Override
    public Map<String, Object> process(RequestMessage request) {
        // Заглушка: имитируем успешный ответ внешней системы.
        Map<String, Object> response = new HashMap<>();
        response.put("existing", true);
        response.put("status", "SUCCESS");
        return response;
    }
}

