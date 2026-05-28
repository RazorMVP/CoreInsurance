package com.nubeero.cia.api.setup.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice IT for the notification template editor endpoints:
 * {@code GET /api/v1/setup/notification-templates},
 * {@code GET /api/v1/setup/notification-templates/defaults},
 * {@code GET /api/v1/setup/notification-templates/variables},
 * {@code POST /api/v1/setup/notification-templates},
 * {@code PUT  /api/v1/setup/notification-templates/{id}},
 * {@code DELETE /api/v1/setup/notification-templates/{id}},
 * {@code POST /api/v1/setup/notification-templates/preview}.
 *
 * <p>Extends {@link FinanceWebItSupport} ({@code @SpringBootTest +
 * @AutoConfigureMockMvc}) — the same base used by {@code NotificationComposerIT}
 * (Task 3.1). The full application context is required so that
 * {@link NotificationTemplateController}, {@link NotificationTemplateService},
 * {@link com.nubeero.cia.documents.notification.MustacheTemplateRenderer},
 * {@link com.nubeero.cia.documents.notification.DefaultTemplateLoader}, Spring
 * Security, and the JPA audit beans all wire together against the Testcontainers
 * PostgreSQL instance migrated to V60 (includes
 * {@code tenant_notification_template}).
 *
 * <p>{@code @AfterEach} performs a hard DELETE so each test starts with an empty
 * override table. {@code FinanceWebItSupport} uses {@code @SpringBootTest} (no
 * implicit transaction rollback).
 *
 * <h2>HTTP status mapping</h2>
 * {@link com.nubeero.cia.common.exception.BusinessRuleException} maps to
 * {@code HttpStatus.UNPROCESSABLE_ENTITY} (422). All three service-level
 * error-code tests ({@code TEMPLATE_TYPE_CHANNEL_CONFLICT},
 * {@code UNKNOWN_TEMPLATE_VARIABLE}, {@code EMPTY_OVERRIDE}) assert
 * {@code 422} and verify the error code in {@code $.errors[0].code}.
 *
 * @since Task 4.3 — F7-δ + R7 NotificationTemplateControllerIT (12 tests)
 */
@WithMockUser(username = "alice", authorities = {
        "notification_templates:view",
        "notification_templates:update"
})
class NotificationTemplateControllerIT extends FinanceWebItSupport {

    private static final String BASE = "/api/v1/setup/notification-templates";

    @Autowired MockMvc       mvc;
    @Autowired ObjectMapper  objectMapper;
    @Autowired TenantNotificationTemplateRepository repo;
    @Autowired JdbcTemplate  jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DELETE FROM tenant_notification_template");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Saves a RECEIPT/EMAIL override directly via the JPA repository. */
    private TenantNotificationTemplate seedReceiptEmailOverride() {
        return repo.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Your receipt {{receiptNumber}}")
                .bodyTemplate("Hi {{customerName}}, amount {{amount}}")
                .build());
    }

    /** Serialises a request body Map to JSON string. */
    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    // 1. listEmpty_returnsEmptyArray
    @Test
    @DisplayName("GET / with no overrides returns empty array")
    void listEmpty_returnsEmptyArray() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // 2. listPopulated_returnsRows
    @Test
    @DisplayName("GET / returns seeded override with correct subjectTemplate")
    void listPopulated_returnsRows() throws Exception {
        seedReceiptEmailOverride();

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].subjectTemplate",
                        containsString("receiptNumber")));
    }

    // 3. getDefaults_returnsAllFourTemplates
    @Test
    @DisplayName("GET /defaults returns exactly 4 default templates (2 types × 2 channels)")
    void getDefaults_returnsAllFourTemplates() throws Exception {
        mvc.perform(get(BASE + "/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults", hasSize(4)));
    }

    // 4. getVariables_returnsAllowlistsForFourCombinations
    @Test
    @DisplayName("GET /variables returns exactly 4 variable-allowlist entries")
    void getVariables_returnsAllowlistsForFourCombinations() throws Exception {
        mvc.perform(get(BASE + "/variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variables", hasSize(4)));
    }

    // 5. createValid_201
    @Test
    @DisplayName("POST with valid RECEIPT/EMAIL body returns 201 with non-null id")
    void createValid_201() throws Exception {
        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Receipt {{receiptNumber}} for {{customerName}}",
                "bodyTemplate", "Dear {{customerName}}, your payment of {{amount}} is confirmed."
        ));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()));
    }

    // 6. createUnknownVariable_422
    @Test
    @DisplayName("POST with unknown Mustache variable returns 422 UNKNOWN_TEMPLATE_VARIABLE")
    void createUnknownVariable_422() throws Exception {
        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "Hi {{notAValidVariable}}"
        ));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code",
                        is("UNKNOWN_TEMPLATE_VARIABLE")));
    }

    // 7. createDuplicate_422
    @Test
    @DisplayName("POST same (type, channel) as existing row returns 422 TEMPLATE_TYPE_CHANNEL_CONFLICT")
    void createDuplicate_422() throws Exception {
        seedReceiptEmailOverride();

        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Duplicate {{receiptNumber}}",
                "bodyTemplate", "Duplicate body {{amount}}"
        ));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code",
                        is("TEMPLATE_TYPE_CHANNEL_CONFLICT")));
    }

    // 8. createBothNull_422
    @Test
    @DisplayName("POST with neither subjectTemplate nor bodyTemplate returns 422 EMPTY_OVERRIDE")
    void createBothNull_422() throws Exception {
        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL"
        ));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code",
                        is("EMPTY_OVERRIDE")));
    }

    // 9. updateExisting_200
    @Test
    @DisplayName("PUT updates the body template and returns 200 with the new value")
    void updateExisting_200() throws Exception {
        TenantNotificationTemplate saved = seedReceiptEmailOverride();
        String updatedBody = "Updated body: {{customerName}} paid {{amount}}";

        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Receipt {{receiptNumber}}",
                "bodyTemplate", updatedBody
        ));

        mvc.perform(put(BASE + "/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bodyTemplate", is(updatedBody)));
    }

    // 10. deleteResetsToDefault_200
    @Test
    @DisplayName("DELETE returns 200 and subsequent GET shows the row is gone")
    void deleteResetsToDefault_200() throws Exception {
        TenantNotificationTemplate saved = seedReceiptEmailOverride();

        mvc.perform(delete(BASE + "/" + saved.getId()))
                .andExpect(status().isOk());

        // Confirm the soft-deleted row no longer appears in the list
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // 11. previewHappy_returnsRenderedSubjectAndBody
    @Test
    @DisplayName("POST /preview renders Mustache template with supplied sample values")
    void previewHappy_returnsRenderedSubjectAndBody() throws Exception {
        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "subjectTemplate", "Receipt {{receiptNumber}}",
                "bodyTemplate", "Hi {{customerName}}, you paid {{amount}}.",
                "sampleValues", Map.of(
                        "receiptNumber", "REC-0001",
                        "customerName", "Amara Nwosu",
                        "amount", "₦50,000"
                )
        ));

        mvc.perform(post(BASE + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject", is("Receipt REC-0001")))
                .andExpect(jsonPath("$.data.body",
                        containsString("Amara Nwosu")));
    }

    // 12. createWithoutAuthority_403
    @Test
    @WithMockUser(username = "bob", authorities = {"notification_templates:view"})
    @DisplayName("POST without notification_templates:update returns 403")
    void createWithoutAuthority_403() throws Exception {
        String body = json(Map.of(
                "templateType", "RECEIPT",
                "channel", "EMAIL",
                "bodyTemplate", "Body {{amount}}"
        ));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
