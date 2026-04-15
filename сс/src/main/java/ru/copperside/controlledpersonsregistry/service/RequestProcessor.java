package ru.copperside.controlledpersonsregistry.service;

import ru.copperside.controlledpersonsregistry.dto.RequestMessage;

import java.util.Map;

public interface RequestProcessor {
    /**
     * Обработать входящий запрос и вернуть payload финального ответа.
     *
     * @return JSON-подобная структура ответа внешней системы.
     *         Обязательное поле: {@code status} со значениями {@code SUCCESS|FAILED}.
     */
    Map<String, Object> process(RequestMessage request);
}

