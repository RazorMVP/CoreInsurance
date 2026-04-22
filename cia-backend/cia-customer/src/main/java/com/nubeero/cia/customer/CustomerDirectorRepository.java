package com.nubeero.cia.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomerDirectorRepository extends JpaRepository<CustomerDirector, UUID> {

    List<CustomerDirector> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId);
}
