package com.nubeero.cia.setup.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface QuoteLoadingTypeRepository extends JpaRepository<QuoteLoadingType, UUID> {
    List<QuoteLoadingType> findAllByDeletedAtIsNullOrderByNameAsc();
    boolean existsByNameIgnoreCaseAndDeletedAtIsNull(String name);
}
