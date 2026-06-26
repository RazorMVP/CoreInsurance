package com.nubeero.cia.api.finance.gl;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.gl.ChartOfAccountService;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PostingRuleService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 Task 3.4 — end-to-end Testcontainers IT for the RM (relationship-manager)
 * commission posting path. Mirrors {@link SubledgerPostingServiceIT} exactly
 * (same {@code @Import} set, same fiscal-period seeding, same loadJe/assertLine
 * helpers) but pins {@code spring.flyway.target} to <strong>"63"</strong> so
 * the V62 commission columns and the V63 {@code POLICY_COMMISSION_RM} posting
 * rule (Dr 5130 / Cr 2520) exist. {@link SubledgerPostingServiceIT} pins "49",
 * which predates both — this IT must NOT inherit that target.
 *
 * <p>An RM-sourced {@link PolicyApprovedEvent} ({@code commissionSourceType =
 * "RELATIONSHIP_MANAGER"}) drives two journal entries: the base premium booking
 * ({@code POLICY_APPROVED}: Dr 1310 / Cr 2110) and the RM commission accrual
 * ({@code POLICY_COMMISSION_RM}: Dr 5130 / Cr 2520 = {@code commissionAmount}).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ChartOfAccountService.class,
    FiscalPeriodResolver.class,
    JournalEntryService.class,
    PostingRuleService.class,
    com.nubeero.cia.finance.gl.PolicyClassResolver.class,
    SubledgerPostingService.class,
    SubledgerPostingRmCommissionIT.TestSupportConfig.class
})
class SubledgerPostingRmCommissionIT {

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
        // Pin to 63 — NOT 49. V62 adds the commission columns and V63 seeds the
        // POLICY_COMMISSION_RM rule (Dr 5130 / Cr 2520) this IT validates.
        registry.add("spring.flyway.target", () -> "63");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private SubledgerPostingService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private LocalDate businessDate;

    @BeforeEach
    void seedFiscalPeriod() {
        businessDate = LocalDate.of(2026, 5, 15);
        UUID fyId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fyId, "MONTH",
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "OPEN", "test");
    }

    /**
     * Builds an RM-sourced PolicyApprovedEvent. Arg order mirrors
     * {@link SubledgerPostingServiceIT#policyApproved()} exactly:
     * (policyId, policyNumber, customerId, customerName, brokerId, brokerName,
     *  productName, netPremium, currencyCode, policyEndDate, productId,
     *  classOfBusinessId, totalSumInsured, policyStartDate,
     *  commissionSourceType, commissionAmount, agentId, agentName).
     */
    private PolicyApprovedEvent rmEvent(UUID policyId) {
        return new PolicyApprovedEvent(
            policyId, "POL-RM-001", UUID.randomUUID(), "Acme", null, null,
            "Motor", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), businessDate,
            "RELATIONSHIP_MANAGER", new BigDecimal("12500.00"), null, null,
            businessDate); // approvalDate = start (test keeps business_date unchanged)
    }

    @Test
    @DisplayName("RM-sourced PolicyApproved → commission JE Dr 5130 / Cr 2520")
    void rmCommissionPostsDr5130Cr2520() {
        UUID policyId = UUID.randomUUID();
        service.onPolicyApproved(rmEvent(policyId));
        entityManager.flush();

        Map<String, Object> je = loadJe("policy", "POLICY_COMMISSION_RM", policyId.toString());
        assertThat(je).isNotEmpty();
        assertThat(je.get("narrative")).isEqualTo("RM commission payable on policy POL-RM-001");
        assertLine((UUID) je.get("id"), "5130", "12500.00", "0.00");
        assertLine((UUID) je.get("id"), "2520", "0.00", "12500.00");
    }

    @Test
    @DisplayName("RM-sourced PolicyApproved → base premium JE still posts")
    void rmCommissionStillBooksBasePremium() {
        UUID policyId = UUID.randomUUID();
        service.onPolicyApproved(rmEvent(policyId));
        entityManager.flush();

        Map<String, Object> premiumJe = loadJe("policy", "POLICY_APPROVED", policyId.toString());
        assertThat(premiumJe).isNotEmpty();
        assertLine((UUID) premiumJe.get("id"), "1310", "500000.00", "0.00");
        assertLine((UUID) premiumJe.get("id"), "2110", "0.00", "500000.00");
    }

    // ── helpers (copied from SubledgerPostingServiceIT) ────────────────────────

    private Map<String, Object> loadJe(String module, String eventType, String reference) {
        return jdbcTemplate.queryForMap(
            "SELECT id, business_date, source_module, source_event_type, source_reference, narrative, status " +
            "FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ?",
            module, eventType, reference);
    }

    private void assertLine(UUID journalEntryId, String accountCode, String expectedDebit, String expectedCredit) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT l.debit_amount, l.credit_amount " +
            "FROM journal_entry_line l " +
            "JOIN chart_of_account a ON a.id = l.account_id " +
            "WHERE l.journal_entry_id = ? AND a.code = ?",
            journalEntryId, accountCode);
        assertThat((BigDecimal) row.get("debit_amount"))
            .as("debit for account " + accountCode)
            .isEqualByComparingTo(expectedDebit);
        assertThat((BigDecimal) row.get("credit_amount"))
            .as("credit for account " + accountCode)
            .isEqualByComparingTo(expectedCredit);
    }

    @TestConfiguration
    @EnableCaching
    static class TestSupportConfig {

        // NOTE: the Clock bean is provided by CiaCommonAutoConfiguration (imported
        // above for @EnableJpaAuditing). Defining one here too would collide on
        // bean name 'clock' and fail context load.

        @Bean
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager(
                ChartOfAccountService.CACHE_BY_CODE,
                ChartOfAccountService.CACHE_BY_IFRS17,
                ChartOfAccountService.CACHE_BY_IFRS9,
                ChartOfAccountService.CACHE_TREE,
                PostingRuleService.CACHE_BY_EVENT_TYPE);
        }
    }
}
