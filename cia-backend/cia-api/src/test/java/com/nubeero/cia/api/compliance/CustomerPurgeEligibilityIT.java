package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class})
class CustomerPurgeEligibilityIT extends ComplianceItSupport {

    @Autowired CustomerPurgeRepository repo;

    @Test
    void selectsOnlyTheInactiveExpiredCustomer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int retentionDays = 2555;

        // (a) ELIGIBLE — inactive, last policy ended 3000 days ago, no ACTIVE policy.
        UUID a = seedCustomer(jdbc, "CUST-A");
        seedPolicy(jdbc, a, "EXPIRED", LocalDate.now().minusDays(3000));

        // (b) INELIGIBLE — has an ACTIVE policy.
        UUID b = seedCustomer(jdbc, "CUST-B");
        seedPolicy(jdbc, b, "ACTIVE", LocalDate.now().minusDays(3000));

        // (c) INELIGIBLE — recently active (policy ended 10 days ago).
        UUID c = seedCustomer(jdbc, "CUST-C");
        seedPolicy(jdbc, c, "EXPIRED", LocalDate.now().minusDays(10));

        // (d) INELIGIBLE — already purged.
        UUID d = seedCustomer(jdbc, "CUST-D");
        seedPolicy(jdbc, d, "EXPIRED", LocalDate.now().minusDays(3000));
        jdbc.update("UPDATE customers SET pii_purged_at = now() WHERE id = ?", d);

        List<UUID> eligible = repo.findEligibleCustomerIds(retentionDays);

        assertThat(eligible).containsExactly(a);
    }

    private UUID seedCustomer(JdbcTemplate jdbc, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, "
                + "first_name, last_name, created_by) VALUES (?,?,?,?,?,?, 'test')",
                id, number, "INDIVIDUAL", "PASSED", "Ada", "Obi");
        return id;
    }

    private void seedPolicy(JdbcTemplate jdbc, UUID customerId, String status, LocalDate endDate) {
        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date, "
                + "total_sum_insured, total_premium, net_premium) "
                + "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
                UUID.randomUUID(), "POL-" + UUID.randomUUID(), status, customerId, "Ada Obi",
                UUID.randomUUID(), "Motor", "MOTOR", 5.0,
                UUID.randomUUID(), "Motor", "MOT",
                "DIRECT", endDate.minusYears(1), endDate, 1000000, 50000, 47500);
    }
}
