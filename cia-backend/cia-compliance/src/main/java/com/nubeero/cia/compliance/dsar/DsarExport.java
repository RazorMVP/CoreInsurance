package com.nubeero.cia.compliance.dsar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A data subject's full footprint, gathered for a DSAR. PII is already decrypted into these maps. */
public record DsarExport(
        Instant generatedAt,
        String customerId,
        String customerNumber,
        Map<String, Object> customer,                 // the decrypted master record
        List<Map<String, Object>> directors,          // decrypted
        List<Map<String, Object>> documents,          // metadata only
        List<Map<String, Object>> policies,
        List<Map<String, Object>> quotes,
        List<Map<String, Object>> claims,
        List<Map<String, Object>> endorsements,
        List<Map<String, Object>> debitNotes,
        List<Map<String, Object>> receipts,
        List<Map<String, Object>> creditNotes,
        List<Map<String, Object>> payments,
        List<Map<String, Object>> auditHistory
) {}
