package ru.copperside.controlledpersonsregistry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import ru.copperside.controlledpersonsregistry.config.ControlledPersonsRegistryProperties;
import ru.copperside.controlledpersonsregistry.dto.RequestMessage;
import ru.copperside.controlledpersonsregistry.exception.MessageProcessingException;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaMessageService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RequestProcessor requestProcessor;

    private final MeterRegistry meterRegistry;

    private final ControlledPersonsRegistryProperties properties;

    /**
     * RetryTemplate с ограничением по времени для {@link MessageProcessingException}.
     * Ретраим попытки, пока не истекло сконфигурированное окно.
     */
    private final RetryTemplate messageProcessingRetryTemplate;

    /**
     * Константы статусов финального ответа.
     * Строки здесь — часть Kafka-контракта и уходят наружу.
     */
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    @KafkaListener(topics = "${controlled-persons-registry.kafka.topics.request}")
    public void listen(ConsumerRecord<String, String> record) {
        String key = record.key();
        String value = record.value();

        // Таймер измеряет end-to-end время обработки сообщения (включая ретраи).
        Timer.Sample processingSample = Timer.start(meterRegistry);

        try {
            log.info(
                    "Received message: key={}, topic={}, partition={}, offset={}, value={}",
                    key, record.topic(), record.partition(), record.offset(), value
            );

            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Kafka message key (id) is required");
            }

            RequestMessage requestMessage = objectMapper.readValue(value, RequestMessage.class);

            // Если id пришёл и в payload, и в key — key считаем source-of-truth.
            if (requestMessage.getId() != null && !key.equals(requestMessage.getId())) {
                log.warn("Payload id does not match Kafka key: payloadId={}, key={}", requestMessage.getId(), key);
            }
            requestMessage.setId(key);

            // Ранее type был отдельным полем в DTO. Сейчас берём его из body (если он там есть),
            // чтобы не терять измерения метрик.
            final String typeTag = extractTypeTag(requestMessage);

            // Ретраим только MessageProcessingException и только в рамках окна времени (см. RetryTemplate).
            messageProcessingRetryTemplate.execute(
                    context -> {
                        try {
                            Map<String, Object> externalResponse = requestProcessor.process(requestMessage);
                            validateFinalResponse(externalResponse);

                            String responseJson = objectMapper.writeValueAsString(externalResponse);
                            kafkaTemplate.send(properties.getKafka().getTopics().getResponse(), key, responseJson);
                            log.info(
                                    "Sent final response to topic {}: key={}, value={}",
                                    properties.getKafka().getTopics().getResponse(), key, responseJson
                            );

                            incrementProcessed(record.topic(), STATUS_SUCCESS, typeTag);
                            recordProcessingTime(processingSample, record.topic(), STATUS_SUCCESS, typeTag);
                            return null;
                        } catch (MessageProcessingException e) {
                            // Ретраимая ошибка (timeouts/сетевые/5xx и т.п.). Пишем в error-topic и ретраим дальше.
                            sendErrorEvent(record, e);
                            incrementRetries(record.topic(), typeTag);
                            log.warn(
                                    "Retryable processing failure (retryCount={}): key={}, error={}",
                                    context.getRetryCount(), key, e.getMessage()
                            );
                            throw e;
                        }
                    },
                    context -> {
                        Throwable last = context.getLastThrowable();
                        String errorText = last != null && last.getMessage() != null
                                ? last.getMessage()
                                : (last != null ? last.getClass().getSimpleName() : "Retry window expired");
                        log.error(
                                "Retry window exhausted (retryCount={}): key={}, error={}",
                                context.getRetryCount(), key, errorText
                        );
                        sendFinalFailed(key, errorText);

                        incrementFailed(record.topic(), typeTag);
                        recordProcessingTime(processingSample, record.topic(), STATUS_FAILED, typeTag);
                        return null;
                    }
            );
        } catch (Exception e) {
            // Неретраимая ошибка: фиксируем в error-topic и отправляем финальный FAILED в response-topic.
            sendErrorEvent(record, e);
            sendFinalFailed(key, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());

            // requestMessage мог не распарситься; type тогда не известен.
            incrementFailed(record.topic(), null);
            recordProcessingTime(processingSample, record.topic(), STATUS_FAILED, null);
        }
    }

    void sendErrorEvent(ConsumerRecord<String, String> record, Exception e) {
        try {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("exception", e.getClass().getName());
            errorResponse.put("topic", record.topic());
            errorResponse.put("partition", record.partition());
            errorResponse.put("offset", record.offset());
            errorResponse.put("key", record.key());
            errorResponse.put("timestamp", System.currentTimeMillis());
            String errorMessage = objectMapper.writeValueAsString(errorResponse);
            kafkaTemplate.send(properties.getKafka().getTopics().getError(), record.key(), errorMessage);
            log.error("Sent error event to topic {}: {}", properties.getKafka().getTopics().getError(), errorMessage);
        } catch (Exception ex) {
            log.error("Failed to send error message", ex);
        }
    }

    private void sendFinalFailed(String key, String errorText) {
        try {
            Map<String, Object> finalFailed = new HashMap<>();
            finalFailed.put("status", STATUS_FAILED);
            finalFailed.put("error", errorText);
            String responseJson = objectMapper.writeValueAsString(finalFailed);
            kafkaTemplate.send(properties.getKafka().getTopics().getResponse(), key, responseJson);
            log.info("Sent final FAILED response to topic {}: key={}, value={}", properties.getKafka().getTopics().getResponse(), key, responseJson);
        } catch (Exception ex) {
            log.error("Failed to send final FAILED response", ex);
        }
    }

    private void validateFinalResponse(Map<String, Object> externalResponse) {
        Object status = externalResponse.get("status");
        if (status == null) {
            throw new IllegalArgumentException("Final response must contain required field 'status'");
        }
    }

    private void incrementProcessed(String topic, String status, String type) {
        Counter.builder("controlled_persons_registry_processed_total")
                .description("Количество сообщений, обработанных с финальным статусом")
                .tag("topic", safeTagValue(topic))
                .tag("status", safeTagValue(status))
                .tag("type", safeTagValue(type))
                .register(meterRegistry)
                .increment();
    }

    private void incrementFailed(String topic, String type) {
        Counter.builder("controlled_persons_registry_failed_total")
                .description("Количество сообщений, завершившихся финальным FAILED")
                .tag("topic", safeTagValue(topic))
                .tag("type", safeTagValue(type))
                .register(meterRegistry)
                .increment();
    }

    private void incrementRetries(String topic, String type) {
        Counter.builder("controlled_persons_registry_retries_total")
                .description("Количество ретраев (MessageProcessingException)")
                .tag("topic", safeTagValue(topic))
                .tag("type", safeTagValue(type))
                .register(meterRegistry)
                .increment();
    }

    private void recordProcessingTime(Timer.Sample sample, String topic, String status, String type) {
        Timer timer = Timer.builder("controlled_persons_registry_processing_seconds")
                .description("End-to-end время обработки сообщения (секунды), включая ретраи")
                .tag("topic", safeTagValue(topic))
                .tag("status", safeTagValue(status))
                .tag("type", safeTagValue(type))
                .register(meterRegistry);

        sample.stop(timer);
    }

    private String safeTagValue(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }

    private String extractTypeTag(RequestMessage requestMessage) {
        if (requestMessage == null || requestMessage.getBody() == null) {
            return null;
        }
        Object v = requestMessage.getBody().get("type");
        if (v == null) {
            v = requestMessage.getBody().get("Type");
        }
        return v != null ? String.valueOf(v) : null;
    }
}
