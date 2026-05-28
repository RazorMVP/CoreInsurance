package com.nubeero.cia.api.finance.notification;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.finance.notification.ComposedMessage;
import com.nubeero.cia.finance.notification.NotificationComposer;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link NotificationComposer} — the domain orchestrator
 * that resolves per-tenant template overrides, falls back to JAR defaults, filters
 * merge fields through the allowlist, and renders via Mustache.
 *
 * <p>Extends {@link FinanceWebItSupport} ({@code @SpringBootTest}) so that
 * {@link NotificationComposer}, {@link com.nubeero.cia.documents.notification.MustacheTemplateRenderer},
 * {@link com.nubeero.cia.documents.notification.DefaultTemplateLoader}, and
 * {@link TenantNotificationTemplateRepository} are all wired in the full
 * application context backed by a real Testcontainers PostgreSQL instance
 * (Flyway migrated to V60, which includes the {@code tenant_notification_template} table).
 *
 * <p>{@code @AfterEach} deletes all rows from {@code tenant_notification_template} so
 * each test starts with a clean override table. {@code FinanceWebItSupport} uses
 * {@code @SpringBootTest} (no implicit transaction rollback), so explicit cleanup is
 * required to prevent override rows from one test leaking into subsequent ones.
 *
 * @since Task 3.1 — F7-δ + R7 NotificationComposer
 */
class NotificationComposerIT extends FinanceWebItSupport {

    @Autowired NotificationComposer composer;
    @Autowired TenantNotificationTemplateRepository templateRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DELETE FROM tenant_notification_template");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Map<String, Object> receiptEmailFields() {
        return Map.of(
                "customerName", "Acme Ltd",
                "amount", "₦450,000.00",
                "paymentDate", "2026-05-27",
                "receiptNumber", "REC-001",
                "debitNoteNumber", "DN-001",
                "companyName", "Tenant Insurance Plc");
    }

    private Map<String, Object> paymentVoucherEmailFields() {
        return Map.of(
                "beneficiaryName", "Bob",
                "amount", "₦100",
                "paymentDate", "2026-05-27",
                "paymentNumber", "PAY-001",
                "creditNoteNumber", "CN-001",
                "companyName", "Tenant Insurance Plc");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no override row → JAR-default subject and body used")
    void noOverride_usesJarDefault() {
        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL, receiptEmailFields());

        assertThat(msg.subject()).isEqualTo("Receipt REC-001 — payment received");
        assertThat(msg.body()).contains("Acme Ltd");
    }

    @Test
    @DisplayName("full override row (both subject + body) → DB values used for both")
    void fullOverride_usesDbValues() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Custom subject for {{receiptNumber}}")
                .bodyTemplate("Custom body for {{customerName}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL, receiptEmailFields());

        assertThat(msg.subject()).isEqualTo("Custom subject for REC-001");
        assertThat(msg.body()).isEqualTo("Custom body for Acme Ltd");
    }

    @Test
    @DisplayName("subject-only override → subject from DB, body from JAR default")
    void subjectOnlyOverride_subjectFromDb_bodyFromJar() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Just the subject {{receiptNumber}}")
                .bodyTemplate(null)
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL, receiptEmailFields());

        assertThat(msg.subject()).isEqualTo("Just the subject REC-001");
        assertThat(msg.body()).contains("Acme Ltd");
    }

    @Test
    @DisplayName("body-only override → subject from JAR default, body from DB")
    void bodyOnlyOverride_subjectFromJar_bodyFromDb() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate(null)
                .bodyTemplate("Body only {{customerName}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL, receiptEmailFields());

        assertThat(msg.subject()).isEqualTo("Receipt REC-001 — payment received");
        assertThat(msg.body()).isEqualTo("Body only Acme Ltd");
    }

    @Test
    @DisplayName("SMS channel → subject is always null regardless of override")
    void smsChannel_subjectIsNull() {
        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS,
                Map.of("customerName", "Acme Ltd", "amount", "₦100", "receiptNumber", "REC-001"));

        assertThat(msg.subject()).isNull();
        assertThat(msg.body()).contains("Acme Ltd").contains("REC-001");
    }

    @Test
    @DisplayName("extra merge fields beyond the allowlist are dropped before render")
    void extraMergeFields_droppedByAllowlistFilter() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.SMS)
                .subjectTemplate(null)
                .bodyTemplate("Customer: {{customerName}}; Receipt: {{receiptNumber}}")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.RECEIPT, NotificationChannel.SMS,
                Map.of("customerName", "Acme Ltd", "amount", "₦100",
                       "receiptNumber", "REC-001", "secretField", "should-not-appear"));

        assertThat(msg.body()).doesNotContain("should-not-appear");
        assertThat(msg.body()).isEqualTo("Customer: Acme Ltd; Receipt: REC-001");
    }

    @Test
    @DisplayName("Mustache conditional section renders correctly")
    void mustacheConditionalSection_renders() {
        templateRepo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.PAYMENT_VOUCHER)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate(null)
                .bodyTemplate("Hi{{#beneficiaryName}} {{beneficiaryName}}{{/beneficiaryName}}!")
                .build());

        ComposedMessage msg = composer.compose(
                NotificationTemplateType.PAYMENT_VOUCHER, NotificationChannel.EMAIL,
                paymentVoucherEmailFields());

        assertThat(msg.body()).isEqualTo("Hi Bob!");
    }
}
