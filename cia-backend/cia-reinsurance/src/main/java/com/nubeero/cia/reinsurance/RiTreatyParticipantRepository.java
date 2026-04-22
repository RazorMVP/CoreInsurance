package com.nubeero.cia.reinsurance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiTreatyParticipantRepository extends JpaRepository<RiTreatyParticipant, UUID> {

    List<RiTreatyParticipant> findByTreatyIdAndDeletedAtIsNull(UUID treatyId);

    Optional<RiTreatyParticipant> findByIdAndDeletedAtIsNull(UUID id);
}
