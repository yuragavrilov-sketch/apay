package ru.copperside.controlledpersonsregistry.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Общие настройки приложения (app-specific), которые не относятся к транспортным автоконфигам Spring Boot.
 *
 * Префикс: {@code controlled-persons-registry.*}
 */
@Data
@Validated
@ConfigurationProperties(prefix = "controlled-persons-registry")
public class ControlledPersonsRegistryProperties {

    /**
     * Выбор реализации {@link ru.copperside.controlledpersonsregistry.service.RequestProcessor}.
     */
    @Valid
    private RequestProcessor requestProcessor = new RequestProcessor();

    @Valid
    private Kafka kafka = new Kafka();

    @Valid
    private Oracle oracle = new Oracle();

    @Data
    public static class RequestProcessor {
        /**
         * Варианты: stub | oracle
         */
        @NotBlank
        private String type = "stub";
    }

    @Data
    public static class Kafka {

        @Valid
        private Topics topics = new Topics();

        @Valid
        private Retry retry = new Retry();

        @Data
        public static class Topics {
            /** Топик входящих запросов */
            @NotBlank
            private String request;

            /** Топик финальных ответов (SUCCESS/FAILED) */
            @NotBlank
            private String response;

            /** Топик событий об ошибках/ретраях */
            @NotBlank
            private String error;
        }

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

    @Data
    public static class Oracle {
        /**
         * Основной запрос в Oracle по документу.
         *
         * Параметры:
         * - :docNumber
         * - :docIssueDate
         */
        @NotBlank
        private String blacklistByDocSql = """
                SELECT 1
                  FROM IBS.Z#UD_CODE_NAME a4,
                       IBS.Z#CLIENT_CHECKS a3,
                       IBS.Z#LEGAL_LIST a2,
                       IBS.Z#BLACKLIST a1
                 WHERE     a1.C_SOURCE_IMPORT = a2.id(+)
                       AND a1.C_TYPE_CHECK = a3.id(+)
                       AND a1.C_MODE_USE = a4.id(+)
                       AND (a1.C_TYPE_CHECK = 648590733064)
                       AND a1.C_DN = :docNumber
                       AND a1.C_DW = :docIssueDate
                       AND sysdate BETWEEN a1.C_DATE_INCLUDE AND nvl(a1.C_DATE_EXCLUDE, to_date('01/01/9999', 'DD/MM/YYYY'))
                """;

        /**
         * Таймаут на выполнение SQL (в секундах).
         */
        @Min(1)
        @Max(120)
        private int queryTimeoutSeconds = 5;
    }
}

