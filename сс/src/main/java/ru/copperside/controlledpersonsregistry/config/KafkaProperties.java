package ru.copperside.controlledpersonsregistry.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация Kafka, читаемая из application-*.yml и/или из Spring Cloud Config Server.
 *
 * <p>Почему через {@link ConfigurationProperties}:
 * <ul>
 *     <li>меньше разрозненных {@code @Value};</li>
 *     <li>валидация конфигурации на старте приложения;</li>
 *     <li>удобное расширение без изменения сигнатур бинов.</li>
 * </ul>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {

    /** bootstrap.servers */
    @NotBlank
    private String bootstrapServers;

    /** group.id для consumer */
    @NotBlank
    private String consumerGroupId;

    /** Топик входящих запросов */
    @NotBlank
    private String requestTopic;

    /** Топик финальных ответов (SUCCESS/FAILED) */
    @NotBlank
    private String responseTopic;

    /** Топик событий об ошибках/ретраях */
    @NotBlank
    private String errorTopic;

    @Valid
    private Retry retry = new Retry();

    @Data
    public static class Retry {

        /**
         * Максимальная длительность окна ретраев в миллисекундах.
         * Пока окно не истекло — ретраим {@code MessageProcessingException}.
         */
        @Min(1)
        private long maxDurationMs = 600_000;

        /** Начальная задержка backoff между попытками (мс). */
        @Min(1)
        private long backoffDelayMs = 60_000;

        /** Множитель экспоненциального backoff. */
        @DecimalMin("1.0")
        private double backoffMultiplier = 1.0;
    }
}

