package com.nubeero.cia.api.customer;

import com.nubeero.cia.api.underwriting.UnderwritingWebItSupport;
import com.nubeero.cia.customer.CustomerStatus;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.customer.KycStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice IT for {@code GET /api/v1/customers} (S5.3 server pagination).
 *
 * <p>Extends {@link UnderwritingWebItSupport} ({@code @SpringBootTest +
 * @AutoConfigureMockMvc}) so the full Spring Security filter chain and
 * {@code @PreAuthorize("hasRole('CUSTOMER_VIEW')")} are active; the mock
 * authorities carry the {@code ROLE_} prefix.
 *
 * <p>Rows are seeded directly via {@link JdbcTemplate}. The encrypted PII test
 * writes the {@code address} column through {@code pgp_sym_encrypt(...,
 * current_setting('app.pii_key'))} (V24 pgcrypto bytea; {@code app.pii_key} is
 * set per Hikari connection by {@code application.yml}'s {@code
 * connection-init-sql}) and asserts the free-text {@code q} search never
 * matches it. {@code @SpringBootTest} tests are not transactional, so every
 * test isolates its assertions with a per-test unique token.
 *
 * @since S5.3 — server pagination for Claims / Customers / Audit
 */
@WithMockUser(username = "alice", authorities = {"ROLE_CUSTOMER_VIEW"})
class CustomerListControllerIT extends UnderwritingWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ fixture helpers

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Seeds one customer row with the plain (non-encrypted) list columns. */
    private UUID seedCustomer(String customerNumber, String firstName, String lastName,
                              String email, String phone, CustomerType type,
                              KycStatus kyc, CustomerStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers (id, customer_number, customer_type, customer_status, "
                + "kyc_status, first_name, last_name, email, phone, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?,'test')",
            id, customerNumber, type.name(), status.name(), kyc.name(),
            firstName, lastName, email, phone);
        return id;
    }

    /** Seeds one customer whose {@code address} (encrypted pgcrypto bytea) carries a token. */
    private UUID seedCustomerWithAddress(String customerNumber, String firstName, String lastName,
                                         String email, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers (id, customer_number, customer_type, customer_status, "
                + "kyc_status, first_name, last_name, email, "
                + "address, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?, "
                + "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
            id, customerNumber, "INDIVIDUAL", "ACTIVE", "PASSED",
            firstName, lastName, email, address);
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void paginationMetaIsPopulated() throws Exception {
        String token = uniq();
        for (int i = 0; i < 25; i++) {
            seedCustomer("CUST-" + token + "-" + String.format("%02d", i),
                    "Ada", "Obi", "user" + token + "@ex.com", "080" + i,
                    CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);
        }

        mockMvc.perform(get("/api/v1/customers")
                        .param("q", token)
                        .param("sort", "customerNumber,asc")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(25)))
                .andExpect(jsonPath("$.data[0].customerNumber").value("CUST-" + token + "-00"));

        mockMvc.perform(get("/api/v1/customers")
                        .param("q", token)
                        .param("sort", "customerNumber,asc")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].customerNumber").value("CUST-" + token + "-10"));
    }

    @Test
    void qNarrowsOnEmailAndName() throws Exception {
        String token = uniq();
        seedCustomer("CUST-" + uniq(), "Ada", "Obi", "target-" + token + "@ex.com", "0801",
                CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);
        seedCustomer("CUST-" + uniq(), "Ngozi", "Eze", "unrelated@ex.com", "0802",
                CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/customers").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].email").value("target-" + token + "@ex.com"));
    }

    @Test
    void statusAndQCombine() throws Exception {
        String token = uniq();
        // Same q token in both emails; only the ACTIVE one matches status=ACTIVE.
        UUID active = seedCustomer("CUST-" + uniq(), "Beta", "One", "combo-a-" + token + "@ex.com",
                "0803", CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);
        seedCustomer("CUST-" + uniq(), "Beta", "Two", "combo-b-" + token + "@ex.com",
                "0804", CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.BLACKLISTED);

        mockMvc.perform(get("/api/v1/customers")
                        .param("status", "ACTIVE")
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(active.toString()))
                .andExpect(jsonPath("$.data[0].customerStatus").value("ACTIVE"));
    }

    @Test
    void sortByCustomerNumberDesc() throws Exception {
        String token = uniq();
        seedCustomer("CUST-" + token + "-1", "Sortable", "A", "s1-" + token + "@ex.com", "0805",
                CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);
        seedCustomer("CUST-" + token + "-3", "Sortable", "C", "s3-" + token + "@ex.com", "0806",
                CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);
        seedCustomer("CUST-" + token + "-2", "Sortable", "B", "s2-" + token + "@ex.com", "0807",
                CustomerType.INDIVIDUAL, KycStatus.PASSED, CustomerStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/customers")
                        .param("q", token)
                        .param("sort", "customerNumber,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].customerNumber").value("CUST-" + token + "-3"));
    }

    @Test
    void qDoesNotMatchEncryptedAddress() throws Exception {
        String numToken = uniq();
        String addrToken = uniq();
        // Row is findable by its plain customerNumber/email (numToken) but its
        // address token (addrToken) lives only in the encrypted bytea column.
        seedCustomerWithAddress("CUST-" + numToken, "Zed", "Zulu",
                "zed-" + numToken + "@ex.com", addrToken + " Marina Street");

        // Searching the address token must NOT surface the row (encrypted → not searchable).
        mockMvc.perform(get("/api/v1/customers").param("q", addrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));

        // Sanity: the row IS there and searchable by a plain column.
        mockMvc.perform(get("/api/v1/customers").param("q", numToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].customerNumber").value("CUST-" + numToken));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_UNDERWRITING_VIEW"})
    void forbiddenWithoutCustomerViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isForbidden());
    }
}
