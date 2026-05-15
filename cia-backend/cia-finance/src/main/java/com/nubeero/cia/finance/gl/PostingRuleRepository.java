package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link PostingRule}. Slice 1.5 reads only —
 * mutations are reserved for a post-Phase-7 tenant customisation epic.
 */
public interface PostingRuleRepository extends JpaRepository<PostingRule, UUID> {

    Optional<PostingRule> findBySourceEventTypeAndIsActiveTrueAndDeletedAtIsNull(String sourceEventType);
}
