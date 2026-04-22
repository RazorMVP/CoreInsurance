package com.nubeero.cia.documents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    @Query("""
            SELECT t FROM DocumentTemplate t
            WHERE t.deletedAt IS NULL
              AND t.active = true
              AND t.templateType = :type
              AND (:productId IS NULL OR t.productId IS NULL OR t.productId = :productId)
              AND (:cobId IS NULL OR t.classOfBusinessId IS NULL OR t.classOfBusinessId = :cobId)
            ORDER BY t.productId NULLS LAST, t.classOfBusinessId NULLS LAST
            """)
    List<DocumentTemplate> findBestMatch(
            @Param("type") DocumentTemplateType type,
            @Param("productId") UUID productId,
            @Param("cobId") UUID classOfBusinessId);

    Page<DocumentTemplate> findAllByDeletedAtIsNull(Pageable pageable);

    Optional<DocumentTemplate> findByIdAndDeletedAtIsNull(UUID id);
}
