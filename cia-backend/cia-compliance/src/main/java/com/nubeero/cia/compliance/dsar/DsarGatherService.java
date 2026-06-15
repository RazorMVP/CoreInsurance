package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers a data subject's full footprint via native SQL against the tenant schema, decrypting the
 * pgcrypto-protected PII columns inline (id_number / id_document_url / address) so the export carries
 * the cleartext the subject is entitled to. Zero business-module deps — all column access is by name.
 */
@Service
@RequiredArgsConstructor
public class DsarGatherService {

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public DsarExport gather(UUID customerId) {
        String id = customerId.toString();

        List<Map<String, Object>> customers = rows(
                "SELECT id, customer_number, customer_type, customer_status, kyc_status, " +
                "  first_name, last_name, other_names, date_of_birth, gender, marital_status, " +
                "  id_type, pgp_sym_decrypt(id_number, current_setting('app.pii_key')) AS id_number, " +
                "  pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) AS id_document_url, " +
                "  company_name, rc_number, incorporation_date, industry, contact_person, " +
                "  email, phone, alternate_phone, " +
                "  pgp_sym_decrypt(address, current_setting('app.pii_key')) AS address, " +
                "  city, state, country, created_at, deleted_at " +
                "FROM customers WHERE id = CAST(:id AS uuid)",
                List.of("id", "customer_number", "customer_type", "customer_status", "kyc_status",
                        "first_name", "last_name", "other_names", "date_of_birth", "gender",
                        "marital_status", "id_type", "id_number", "id_document_url", "company_name", "rc_number",
                        "incorporation_date", "industry", "contact_person", "email", "phone",
                        "alternate_phone", "address", "city", "state", "country", "created_at",
                        "deleted_at"),
                id);
        if (customers.isEmpty()) {
            throw new ResourceNotFoundException("Customer", customerId);
        }
        Map<String, Object> customer = customers.get(0);

        List<Map<String, Object>> directors = rows(
                "SELECT id, first_name, last_name, date_of_birth, id_type, " +
                "  pgp_sym_decrypt(id_number, current_setting('app.pii_key')) AS id_number, " +
                "  pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) AS id_document_url, " +
                "  kyc_status FROM customer_directors WHERE customer_id = CAST(:id AS uuid) " +
                "  AND deleted_at IS NULL",
                List.of("id", "first_name", "last_name", "date_of_birth", "id_type", "id_number",
                        "id_document_url", "kyc_status"), id);

        List<Map<String, Object>> documents = rows(
                "SELECT id, document_type, document_name, document_path, mime_type, created_at " +
                "FROM customer_documents WHERE customer_id = CAST(:id AS uuid) AND deleted_at IS NULL",
                List.of("id", "document_type", "document_name", "document_path", "mime_type",
                        "created_at"), id);

        List<Map<String, Object>> policies = rows(
                "SELECT policy_number, status, business_type, policy_start_date, policy_end_date, " +
                "  total_sum_insured, total_premium, net_premium, created_at " +
                "FROM policies WHERE customer_id = CAST(:id AS uuid)",
                List.of("policy_number", "status", "business_type", "policy_start_date",
                        "policy_end_date", "total_sum_insured", "total_premium", "net_premium",
                        "created_at"), id);

        List<Map<String, Object>> quotes = rows(
                "SELECT quote_number, status, business_type, policy_start_date, policy_end_date, " +
                "  total_sum_insured, net_premium, created_at " +
                "FROM quotes WHERE customer_id = CAST(:id AS uuid)",
                List.of("quote_number", "status", "business_type", "policy_start_date",
                        "policy_end_date", "total_sum_insured", "net_premium", "created_at"), id);

        List<Map<String, Object>> claims = rows(
                "SELECT claim_number, status, policy_number, incident_date, reported_date, " +
                "  description, estimated_loss, reserve_amount, approved_amount, created_at " +
                "FROM claims WHERE customer_id = CAST(:id AS uuid)",
                List.of("claim_number", "status", "policy_number", "incident_date", "reported_date",
                        "description", "estimated_loss", "reserve_amount", "approved_amount",
                        "created_at"), id);

        List<Map<String, Object>> endorsements = rows(
                "SELECT endorsement_number, status, endorsement_type, policy_number, effective_date, " +
                "  premium_adjustment, description, created_at " +
                "FROM endorsements WHERE customer_id = CAST(:id AS uuid)",
                List.of("endorsement_number", "status", "endorsement_type", "policy_number",
                        "effective_date", "premium_adjustment", "description", "created_at"), id);

        List<Map<String, Object>> debitNotes = rows(
                "SELECT debit_note_number, status, entity_type, entity_reference, description, " +
                "  total_amount, paid_amount, currency_code, due_date, created_at " +
                "FROM debit_notes WHERE customer_id = CAST(:id AS uuid)",
                List.of("debit_note_number", "status", "entity_type", "entity_reference",
                        "description", "total_amount", "paid_amount", "currency_code", "due_date",
                        "created_at"), id);

        List<Map<String, Object>> receipts = rows(
                "SELECT r.receipt_number, r.amount, r.payment_date, r.payment_method, r.status, " +
                "  r.created_at FROM receipts r JOIN debit_notes dn ON dn.id = r.debit_note_id " +
                "WHERE dn.customer_id = CAST(:id AS uuid)",
                List.of("receipt_number", "amount", "payment_date", "payment_method", "status",
                        "created_at"), id);

        List<Map<String, Object>> creditNotes = rows(
                "SELECT credit_note_number, status, entity_type, entity_reference, description, " +
                "  total_amount, paid_amount, currency_code, due_date, created_at " +
                "FROM credit_notes WHERE beneficiary_id = CAST(:id AS uuid)",
                List.of("credit_note_number", "status", "entity_type", "entity_reference",
                        "description", "total_amount", "paid_amount", "currency_code", "due_date",
                        "created_at"), id);

        List<Map<String, Object>> payments = rows(
                "SELECT p.payment_number, p.amount, p.payment_date, p.payment_method, p.status, " +
                "  p.created_at FROM payments p JOIN credit_notes cn ON cn.id = p.credit_note_id " +
                "WHERE cn.beneficiary_id = CAST(:id AS uuid)",
                List.of("payment_number", "amount", "payment_date", "payment_method", "status",
                        "created_at"), id);

        List<Map<String, Object>> auditHistory = rows(
                "SELECT action, user_name, \"timestamp\", reason FROM audit_log " +
                "WHERE entity_id = :id ORDER BY \"timestamp\"",
                List.of("action", "user_name", "timestamp", "reason"), id);

        return new DsarExport(Instant.now(), id, str(customer.get("customer_number")),
                customer, directors, documents, policies, quotes, claims, endorsements,
                debitNotes, receipts, creditNotes, payments, auditHistory);
    }

    /**
     * Runs a native query whose SELECT aliases match {@code keys} (in order) and maps each row to a map.
     * Every gather query is multi-column, so each row is an {@code Object[]}. A single-column SELECT would
     * make Hibernate return {@code List<Object>} (not {@code Object[]}) and break the cast — keep ≥2 columns.
     */
    private List<Map<String, Object>> rows(String sql, List<String> keys, String id) {
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("id", id);
        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        return raw.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.size() && i < r.length; i++) m.put(keys.get(i), r[i]);
            return m;
        }).toList();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
