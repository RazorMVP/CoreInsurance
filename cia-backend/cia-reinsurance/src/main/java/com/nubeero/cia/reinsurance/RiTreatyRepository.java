package com.nubeero.cia.reinsurance;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiTreatyRepository extends JpaRepository<RiTreaty, UUID> {

    @Query("""
            SELECT t FROM RiTreaty t
            WHERE t.deletedAt IS NULL
              AND (:type IS NULL OR t.treatyType = :type)
              AND (:status IS NULL OR t.status = :status)
              AND (:year IS NULL OR t.treatyYear = :year)
            """)
    Page<RiTreaty> findAll(
            @Param("type") TreatyType type,
            @Param("status") TreatyStatus status,
            @Param("year") Integer year,
            Pageable pageable);

    @Query("""
            SELECT t FROM RiTreaty t
            WHERE t.deletedAt IS NULL
              AND t.status = 'ACTIVE'
              AND t.treatyYear = :year
              AND (:cobId IS NULL OR t.classOfBusinessId IS NULL OR t.classOfBusinessId = :cobId)
              AND (:productId IS NULL OR t.productId IS NULL OR t.productId = :productId)
              AND t.treatyType = :type
            ORDER BY t.classOfBusinessId NULLS LAST, t.productId NULLS LAST
            """)
    List<RiTreaty> findActiveTreaties(
            @Param("year") int year,
            @Param("cobId") UUID classOfBusinessId,
            @Param("productId") UUID productId,
            @Param("type") TreatyType type);

    Optional<RiTreaty> findByIdAndDeletedAtIsNull(UUID id);
}
