package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.DataSource;
import com.nubeero.cia.reports.domain.ReportConfig;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportField;
import com.nubeero.cia.reports.domain.ReportFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReportQueryBuilderIntegrationTest {

    private JdbcTemplate adminJdbcTemplate;
    private JdbcTemplate reportJdbcTemplate;
    private ReportQueryBuilder queryBuilder;
    private String schemaName;
    private boolean schemaCreated;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        schemaName = "report_it_" + suffix;

        adminJdbcTemplate = new JdbcTemplate(dataSource(baseUrl()));
        assumeTrue(canConnect(), "Local Docker-backed PostgreSQL is not reachable for report SQL tests");

        adminJdbcTemplate.execute("CREATE SCHEMA " + schemaName);
        schemaCreated = true;
        reportJdbcTemplate = new JdbcTemplate(dataSource(schemaUrl()));
        queryBuilder = new ReportQueryBuilder(reportJdbcTemplate);
        createTables();
        seedRows();
    }

    @AfterEach
    void tearDown() {
        if (adminJdbcTemplate != null && schemaName != null && schemaCreated) {
            adminJdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
        }
    }

    @Test
    void runsPolicyReportAgainstMigratedColumnNamesAndFilters() {
        ReportDefinition definition = definition(
                DataSource.POLICIES,
                ReportConfig.builder()
                        .fields(List.of(
                                field("policy_number"),
                                field("customer_name"),
                                field("sum_insured"),
                                field("premium"),
                                field("status"),
                                field("start_date"),
                                field("end_date")
                        ))
                        .filters(List.of(
                                filter("status"),
                                filter("date_from"),
                                filter("date_to")
                        ))
                        .sortBy("policy_number")
                        .sortDir("ASC")
                        .build()
        );

        List<Map<String, Object>> rows = queryBuilder.execute(definition, Map.of(
                "status", "ACTIVE",
                "date_from", "2026-01-01",
                "date_to", "2026-12-31"
        ));

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsEntry("policy_number", "POL-001")
                .containsEntry("customer_name", "Alpha Insured")
                .containsEntry("status", "ACTIVE");
        assertThat((BigDecimal) rows.getFirst().get("sum_insured")).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat((BigDecimal) rows.getFirst().get("premium")).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void groupsSelectedPolicyFieldsAndKeepsAliasesAligned() {
        ReportDefinition definition = definition(
                DataSource.POLICIES,
                ReportConfig.builder()
                        .fields(List.of(
                                field("class_of_business"),
                                field("product_name"),
                                field("premium")
                        ))
                        .groupBy("class_of_business")
                        .sortBy("premium")
                        .sortDir("DESC")
                        .build()
        );

        List<Map<String, Object>> rows = queryBuilder.execute(definition, Map.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("class_of_business", "Motor")
                .containsEntry("product_name", "Motor Comprehensive");
        assertThat((BigDecimal) rows.getFirst().get("premium")).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void runsSeededReportShapesAcrossAllDataSources() {
        assertThat(queryBuilder.execute(definition(DataSource.CLAIMS, fields(
                "claim_number",
                "policy_number",
                "customer_name",
                "reserve_amount",
                "total_paid",
                "registered_at"
        )), Map.of())).hasSize(1);

        assertThat(queryBuilder.execute(definition(DataSource.FINANCE, fields(
                "debit_note_number",
                "policy_number",
                "customer_name",
                "amount",
                "due_date",
                "status"
        )), Map.of())).hasSize(1);

        assertThat(queryBuilder.execute(definition(DataSource.REINSURANCE, fields(
                "policy_number",
                "treaty_name",
                "treaty_type",
                "retained_amount",
                "ceded_amount",
                "status"
        )), Map.of())).hasSize(1);

        assertThat(queryBuilder.execute(definition(DataSource.CUSTOMERS, fields(
                "full_name",
                "customer_type",
                "kyc_status",
                "channel"
        )), Map.of())).hasSize(1);

        assertThat(queryBuilder.execute(definition(DataSource.ENDORSEMENTS, fields(
                "endorsement_number",
                "policy_number",
                "customer_name",
                "endorsement_type",
                "premium",
                "effective_date",
                "status"
        )), Map.of())).hasSize(1);
    }

    private boolean canConnect() {
        try {
            adminJdbcTemplate.execute("SELECT 1");
            return true;
        } catch (CannotGetJdbcConnectionException ex) {
            return false;
        }
    }

    private void createTables() {
        reportJdbcTemplate.execute(
                """
                CREATE TABLE policies (
                    id UUID PRIMARY KEY,
                    policy_number VARCHAR(60),
                    status VARCHAR(30),
                    customer_id UUID,
                    customer_name VARCHAR(200),
                    product_id UUID,
                    product_name VARCHAR(100),
                    class_of_business_id UUID,
                    class_of_business_name VARCHAR(100),
                    policy_start_date DATE,
                    policy_end_date DATE,
                    total_sum_insured DECIMAL(18, 2),
                    total_premium DECIMAL(18, 2),
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE claims (
                    id UUID PRIMARY KEY,
                    claim_number VARCHAR(30),
                    status VARCHAR(30),
                    policy_id UUID,
                    policy_number VARCHAR(60),
                    customer_id UUID,
                    customer_name VARCHAR(200),
                    product_id UUID,
                    product_name VARCHAR(100),
                    class_of_business_id UUID,
                    class_of_business_name VARCHAR(100),
                    reserve_amount DECIMAL(18, 2),
                    approved_amount DECIMAL(18, 2),
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE debit_notes (
                    id UUID PRIMARY KEY,
                    debit_note_number VARCHAR(30),
                    status VARCHAR(20),
                    entity_reference VARCHAR(60),
                    customer_id UUID,
                    customer_name VARCHAR(200),
                    total_amount DECIMAL(18, 2),
                    due_date DATE,
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE ri_treaties (
                    id UUID PRIMARY KEY,
                    treaty_type VARCHAR(30),
                    description TEXT,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE ri_allocations (
                    id UUID PRIMARY KEY,
                    policy_number VARCHAR(60),
                    treaty_id UUID,
                    treaty_type VARCHAR(30),
                    status VARCHAR(30),
                    retained_amount DECIMAL(18, 2),
                    ceded_amount DECIMAL(18, 2),
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE customers (
                    id UUID PRIMARY KEY,
                    customer_type VARCHAR(20),
                    customer_status VARCHAR(20),
                    kyc_status VARCHAR(20),
                    first_name VARCHAR(100),
                    last_name VARCHAR(100),
                    company_name VARCHAR(200),
                    contact_person VARCHAR(200),
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
        reportJdbcTemplate.execute(
                """
                CREATE TABLE endorsements (
                    id UUID PRIMARY KEY,
                    endorsement_number VARCHAR(30),
                    status VARCHAR(30),
                    endorsement_type VARCHAR(30),
                    policy_id UUID,
                    policy_number VARCHAR(60),
                    customer_id UUID,
                    customer_name VARCHAR(200),
                    product_id UUID,
                    product_name VARCHAR(100),
                    class_of_business_id UUID,
                    class_of_business_name VARCHAR(100),
                    effective_date DATE,
                    policy_end_date DATE,
                    new_sum_insured DECIMAL(18, 2),
                    premium_adjustment DECIMAL(18, 2),
                    created_at TIMESTAMPTZ,
                    deleted_at TIMESTAMPTZ
                )
                """
        );
    }

    private void seedRows() {
        UUID customerId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID treatyId = UUID.randomUUID();

        reportJdbcTemplate.update(
                """
                INSERT INTO policies (
                    id, policy_number, status, customer_id, customer_name, product_id, product_name,
                    class_of_business_id, class_of_business_name, policy_start_date, policy_end_date,
                    total_sum_insured, total_premium, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, DATE '2026-01-01', DATE '2026-12-31', ?, ?, TIMESTAMPTZ '2026-01-05 10:00:00+00')
                """,
                policyId, "POL-001", "ACTIVE", customerId, "Alpha Insured", productId, "Motor Comprehensive",
                classId, "Motor", new BigDecimal("10000.00"), new BigDecimal("100.00")
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO policies (
                    id, policy_number, status, customer_id, customer_name, product_id, product_name,
                    class_of_business_id, class_of_business_name, policy_start_date, policy_end_date,
                    total_sum_insured, total_premium, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, DATE '2026-02-01', DATE '2026-12-31', ?, ?, TIMESTAMPTZ '2026-02-05 10:00:00+00')
                """,
                UUID.randomUUID(), "POL-002", "ACTIVE", customerId, "Alpha Insured", productId, "Motor Comprehensive",
                classId, "Motor", new BigDecimal("20000.00"), new BigDecimal("200.00")
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO claims (
                    id, claim_number, status, policy_id, policy_number, customer_id, customer_name,
                    product_id, product_name, class_of_business_id, class_of_business_name,
                    reserve_amount, approved_amount, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-03-01 10:00:00+00')
                """,
                UUID.randomUUID(), "CLM-001", "APPROVED", policyId, "POL-001", customerId, "Alpha Insured",
                productId, "Motor Comprehensive", classId, "Motor", new BigDecimal("500.00"), new BigDecimal("250.00")
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO debit_notes (
                    id, debit_note_number, status, entity_reference, customer_id, customer_name,
                    total_amount, due_date, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, DATE '2026-04-01', TIMESTAMPTZ '2026-03-15 10:00:00+00')
                """,
                UUID.randomUUID(), "DN-001", "OUTSTANDING", "POL-001", customerId, "Alpha Insured", new BigDecimal("115.00")
        );
        reportJdbcTemplate.update(
                "INSERT INTO ri_treaties (id, treaty_type, description) VALUES (?, ?, ?)",
                treatyId, "QUOTA_SHARE", "2026 Motor Treaty"
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO ri_allocations (
                    id, policy_number, treaty_id, treaty_type, status, retained_amount, ceded_amount, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-03-20 10:00:00+00')
                """,
                UUID.randomUUID(), "POL-001", treatyId, "QUOTA_SHARE", "ACTIVE", new BigDecimal("6000.00"), new BigDecimal("4000.00")
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO customers (
                    id, customer_type, customer_status, kyc_status, first_name, last_name, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-01-01 10:00:00+00')
                """,
                customerId, "INDIVIDUAL", "ACTIVE", "VERIFIED", "Alpha", "Insured"
        );
        reportJdbcTemplate.update(
                """
                INSERT INTO endorsements (
                    id, endorsement_number, status, endorsement_type, policy_id, policy_number, customer_id,
                    customer_name, product_id, product_name, class_of_business_id, class_of_business_name,
                    effective_date, policy_end_date, new_sum_insured, premium_adjustment, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, DATE '2026-05-01', DATE '2026-12-31', ?, ?, TIMESTAMPTZ '2026-05-01 10:00:00+00')
                """,
                UUID.randomUUID(), "END-001", "APPROVED", "SUM_INSURED_INCREASE", policyId, "POL-001", customerId,
                "Alpha Insured", productId, "Motor Comprehensive", classId, "Motor",
                new BigDecimal("15000.00"), new BigDecimal("50.00")
        );
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("CIA_TEST_DB_USERNAME", "cia"));
        dataSource.setPassword(System.getenv().getOrDefault("CIA_TEST_DB_PASSWORD", "cia_dev"));
        return dataSource;
    }

    private String baseUrl() {
        return System.getenv().getOrDefault("CIA_TEST_DB_URL", "jdbc:postgresql://localhost:5434/cia");
    }

    private String schemaUrl() {
        String separator = baseUrl().contains("?") ? "&" : "?";
        return baseUrl() + separator + "currentSchema=" + schemaName;
    }

    private ReportDefinition definition(DataSource dataSource, ReportConfig config) {
        return ReportDefinition.builder()
                .dataSource(dataSource)
                .config(config)
                .build();
    }

    private ReportDefinition definition(DataSource dataSource, List<ReportField> fields) {
        return definition(dataSource, ReportConfig.builder().fields(fields).build());
    }

    private List<ReportField> fields(String... keys) {
        return List.of(keys).stream().map(this::field).toList();
    }

    private ReportField field(String key) {
        return ReportField.builder().key(key).build();
    }

    private ReportFilter filter(String key) {
        return ReportFilter.builder().key(key).build();
    }
}
