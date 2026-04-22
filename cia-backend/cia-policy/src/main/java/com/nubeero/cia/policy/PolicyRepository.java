package com.nubeero.cia.policy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    Page<Policy> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Policy> findAllByStatusAndDeletedAtIsNull(PolicyStatus status, Pageable pageable);

    Page<Policy> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);

    Optional<Policy> findByPolicyNumberAndDeletedAtIsNull(String policyNumber);

    Optional<Policy> findByQuoteIdAndDeletedAtIsNull(UUID quoteId);

    List<Policy> findAllByStatusAndPolicyEndDateBeforeAndDeletedAtIsNull(
            PolicyStatus status, LocalDate date);

    @Query("""
            SELECT p FROM Policy p
            WHERE p.deletedAt IS NULL
              AND (LOWER(p.policyNumber)          LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.customerName)          LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.productName)           LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.classOfBusinessName)   LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.brokerName)            LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.quoteNumber)           LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Policy> search(@Param("q") String query, Pageable pageable);
}
