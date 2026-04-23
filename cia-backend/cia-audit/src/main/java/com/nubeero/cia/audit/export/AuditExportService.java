package com.nubeero.cia.audit.export;

import com.nubeero.cia.audit.log.dto.AuditLogFilter;
import com.nubeero.cia.common.audit.AuditLog;
import com.nubeero.cia.common.audit.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditExportService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public StreamingResponseBody exportCsv(AuditLogFilter filter) {
        List<AuditLog> logs = auditLogRepository.findAll(buildSpec(filter));
        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                    .setHeader("ID", "Entity Type", "Entity ID", "Action",
                            "User ID", "User Name", "Timestamp",
                            "IP Address", "Approval Amount")
                    .build())) {
                for (AuditLog log : logs) {
                    printer.printRecord(
                            log.getId(),
                            log.getEntityType(),
                            log.getEntityId(),
                            log.getAction(),
                            log.getUserId(),
                            log.getUserName(),
                            log.getTimestamp(),
                            log.getIpAddress(),
                            log.getApprovalAmount()
                    );
                }
            }
        };
    }

    private Specification<AuditLog> buildSpec(AuditLogFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.getEntityType() != null && !filter.getEntityType().isBlank())
                predicates.add(cb.equal(root.get("entityType"), filter.getEntityType()));
            if (filter.getEntityId() != null && !filter.getEntityId().isBlank())
                predicates.add(cb.equal(root.get("entityId"), filter.getEntityId()));
            if (filter.getUserId() != null && !filter.getUserId().isBlank())
                predicates.add(cb.equal(root.get("userId"), filter.getUserId()));
            if (filter.getAction() != null)
                predicates.add(cb.equal(root.get("action"), filter.getAction()));
            if (filter.getFrom() != null)
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.getFrom()));
            if (filter.getTo() != null)
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), filter.getTo()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
