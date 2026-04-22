package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrokerRepository extends JpaRepository<Broker, UUID> {
    Optional<Broker> findByCodeAndDeletedAtIsNull(String code);
    Page<Broker> findAllByDeletedAtIsNull(Pageable pageable);
}
