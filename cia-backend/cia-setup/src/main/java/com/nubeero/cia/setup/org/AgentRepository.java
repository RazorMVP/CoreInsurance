package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Page<Agent> findAllByDeletedAtIsNull(Pageable pageable);
}
