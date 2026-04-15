package ru.copperside.controlledpersonsregistry.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.copperside.controlledpersonsregistry.config.ControlledPersonsRegistryProperties;

import java.sql.Date;
import java.time.LocalDate;

/**
 * Репозиторий для Oracle-запроса поиска в blacklist по реквизитам документа.
 */
@Repository
@Slf4j
@ConditionalOnProperty(prefix = "controlled-persons-registry.request-processor", name = "type", havingValue = "oracle")
public class OracleRegistryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ControlledPersonsRegistryProperties properties;

    public OracleRegistryRepository(NamedParameterJdbcTemplate jdbc, ControlledPersonsRegistryProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;

        this.jdbc.getJdbcTemplate().setQueryTimeout(properties.getOracle().getQueryTimeoutSeconds());
    }

    public boolean existsByDoc(String docNumber, LocalDate docIssueDate) throws DataAccessException {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("docNumber", truncate(docNumber, 1024))
                .addValue("docIssueDate", Date.valueOf(docIssueDate).toString());

        ResultSetExtractor<Boolean> extractor = rs -> rs.next();
        Boolean exists = jdbc.query(properties.getOracle().getBlacklistByDocSql(), params, extractor);
        return Boolean.TRUE.equals(exists);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}

