package com.nubeero.cia.quotation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    Page<Quote> findAllByDeletedAtIsNull(Pageable pageable);

    Page<Quote> findAllByStatusAndDeletedAtIsNull(QuoteStatus status, Pageable pageable);

    Page<Quote> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);

    Optional<Quote> findByQuoteNumberAndDeletedAtIsNull(String quoteNumber);

    @Query("""
            SELECT q FROM Quote q
            WHERE q.deletedAt IS NULL
              AND (LOWER(q.quoteNumber)    LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(q.customerName)  LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(q.productName)   LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(q.brokerName)    LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Quote> search(@Param("q") String query, Pageable pageable);
}
