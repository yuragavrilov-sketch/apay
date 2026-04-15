package ru.copperside.controlledpersonsregistry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ru.copperside.controlledpersonsregistry.dto.RequestMessage;
import ru.copperside.controlledpersonsregistry.exception.MessageProcessingException;
import ru.copperside.controlledpersonsregistry.repository.OracleRegistryRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Каркас обработчика, который ходит в Oracle.
 *
 * Включается через конфиг: controlled-persons-registry.request-processor.type=oracle
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "controlled-persons-registry.request-processor", name = "type", havingValue = "oracle")
public class OracleRequestProcessor implements RequestProcessor {

    private final OracleRegistryRepository oracleRegistryRepository;

    @Override
    public Map<String, Object> process(RequestMessage request) {
        String docNumber = resolveDocNumber(request);
        LocalDate docIssueDate = resolveDocIssueDate(request);

        if (docNumber == null || docNumber.isBlank()) {
            Map<String, Object> failed = new HashMap<>();
            failed.put("status", "FAILED");
            failed.put("error", "docNumber is required for oracle processing");
            return failed;
        }
        if (docIssueDate == null) {
            Map<String, Object> failed = new HashMap<>();
            failed.put("status", "FAILED");
            failed.put("error", "docIssueDate is required for oracle processing (expected yyyy-MM-dd or dd.MM.yyyy)");
            return failed;
        }

        try {
            boolean exists = oracleRegistryRepository.existsByDoc(docNumber, docIssueDate);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("existing", exists);
            response.put("docNumber", docNumber);
            response.put("docIssueDate", docIssueDate.toString());
            return response;
        } catch (DataAccessException e) {
            // Для первого каркаса считаем DB-ошибки ретраимыми.
            // При уточнении требований можно будет разделить transient/non-transient ошибки.
            throw new MessageProcessingException("Oracle query failed", e);
        }
    }

    private String resolveDocNumber(RequestMessage request) {
        if (request == null || request.getBody() == null) {
            return null;
        }
        Object v = request.getBody().get("docNumber");
        if (v == null) {
            v = request.getBody().get("DocNumber");
        }
        return v != null ? String.valueOf(v) : null;
    }

    private LocalDate resolveDocIssueDate(RequestMessage request) {
        if (request == null || request.getBody() == null) {
            return null;
        }
        Object v = request.getBody().get("docIssueDate");
        if (v == null) {
            v = request.getBody().get("DocIssueDate");
        }
        if (v == null) {
            return null;
        }

        if (v instanceof String s) {
            s = s.trim();
            if (s.isEmpty()) {
                return null;
            }
            // Пытаемся несколько популярных форматов.
            for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd.MM.yyyy"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy")
            }) {
                try {
                    return LocalDate.parse(s, fmt);
                } catch (DateTimeParseException ignored) {
                    // try next
                }
            }
            return null;
        }

        if (v instanceof Number n) {
            // epoch millis
            return Instant.ofEpochMilli(n.longValue()).atZone(ZoneId.systemDefault()).toLocalDate();
        }

        return null;
    }
}

