package ru.copperside.controlledpersonsregistry.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Результат запроса blacklist-by-doc-sql.
 *
 */
public record BlacklistRecord(
        Long classId,
        String docNumber,
        LocalDate docIssueDate,
        LocalDateTime dateInclude,
        LocalDateTime dateExclude,
        String typeCheckCode
) {
}

