package com.nubeero.cia.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomerDocumentRepository extends JpaRepository<CustomerDocument, UUID> {

    List<CustomerDocument> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId);
}
