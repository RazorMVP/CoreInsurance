package com.nubeero.cia.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Page<Customer> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Customer> findAllByCustomerTypeAndDeletedAtIsNull(CustomerType customerType, Pageable pageable);

    Page<Customer> findAllByKycStatusAndDeletedAtIsNull(KycStatus kycStatus, Pageable pageable);

    @Query("""
            SELECT c FROM Customer c WHERE c.deletedAt IS NULL AND (
              LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR
              LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%')) OR
              LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%')) OR
              LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) OR
              c.phone LIKE CONCAT('%', :q, '%') OR
              c.rcNumber LIKE CONCAT('%', :q, '%')
            )""")
    Page<Customer> search(@Param("q") String query, Pageable pageable);
}
