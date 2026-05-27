package com.nubeero.cia.api.setup.notification;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;
import com.nubeero.cia.setup.notification.TenantNotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link TenantNotificationTemplateRepository} against
 * a real PostgreSQL container with all Flyway migrations applied (through V60).
 *
 * <p>Three smoke-tests:
 * <ol>
 *   <li>Round-trip persist + retrieve by {@code (template_type, channel)}</li>
 *   <li>{@code ck_tnt_at_least_one_override} CHECK fires when both subject +
 *       body are null</li>
 *   <li>{@code ck_tnt_sms_no_subject} CHECK fires when an SMS row has a
 *       non-null subject</li>
 * </ol>
 *
 * <p>Pattern mirrors {@code FinanceItSupport}: {@code @DataJpaTest} +
 * {@code @AutoConfigureTestDatabase(NONE)} + Testcontainers + Flyway target
 * pinned to V60 (the current migration tip).
 *
 * @since Task 1.3 — F7-δ + R7 schema smoke-tests
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
class TenantNotificationTemplateRepositoryIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "60");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired
    TenantNotificationTemplateRepository repository;

    @Test
    void persistsAndRetrievesByTypeAndChannel() {
        repository.save(TenantNotificationTemplate.builder()
                .templateType(NotificationTemplateType.RECEIPT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Receipt {{receiptNumber}} — paid")
                .bodyTemplate("Hi {{customerName}}")
                .build());

        var found = repository.findByTemplateTypeAndChannel(
                NotificationTemplateType.RECEIPT, NotificationChannel.EMAIL);

        assertThat(found).isPresent();
        assertThat(found.get().getSubjectTemplate()).contains("{{receiptNumber}}");
    }

    @Test
    void rejectsRowWithBothFieldsNull_atLeastOneOverride() {
        assertThatThrownBy(() ->
            repository.saveAndFlush(TenantNotificationTemplate.builder()
                    .templateType(NotificationTemplateType.RECEIPT)
                    .channel(NotificationChannel.EMAIL)
                    .subjectTemplate(null)
                    .bodyTemplate(null)
                    .build())
        ).isInstanceOf(DataIntegrityViolationException.class)
         .hasMessageContaining("ck_tnt_at_least_one_override");
    }

    @Test
    void rejectsSmsRowWithSubject_ckSmsNoSubject() {
        assertThatThrownBy(() ->
            repository.saveAndFlush(TenantNotificationTemplate.builder()
                    .templateType(NotificationTemplateType.RECEIPT)
                    .channel(NotificationChannel.SMS)
                    .subjectTemplate("This subject should be rejected")
                    .bodyTemplate("Body")
                    .build())
        ).isInstanceOf(DataIntegrityViolationException.class)
         .hasMessageContaining("ck_tnt_sms_no_subject");
    }
}
