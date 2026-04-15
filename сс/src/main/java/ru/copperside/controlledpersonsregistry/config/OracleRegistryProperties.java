package ru.copperside.controlledpersonsregistry.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки Oracle-запросов для поиска в реестре контролируемых лиц.
 *
 * Важно: DataSource настраивается стандартными свойствами Spring Boot:
 * {@code spring.datasource.url/username/password/driver-class-name}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "oracle.registry")
public class OracleRegistryProperties {

    /**
     * Пример запроса: проверка существования записи по EXT_ID.
     *
     * Параметры: :extId
     */
    @NotBlank
    private String existsByExtIdSql = "select 1 from CONTROLLED_PERSONS where EXT_ID = :extId fetch first 1 row only";

    /**
     * Таймаут на выполнение SQL (в секундах).
     */
    @Min(1)
    @Max(120)
    private int queryTimeoutSeconds = 5;
}

