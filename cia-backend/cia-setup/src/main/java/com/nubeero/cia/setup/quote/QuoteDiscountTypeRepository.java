package com.nubeero.cia.setup.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface QuoteDiscountTypeRepository extends JpaRepository<QuoteDiscountType, UUID> {
    List<QuoteDiscountType> findAllByDeletedAtIsNullOrderByNameAsc();
    boolean existsByNameIgnoreCaseAndDeletedAtIsNull(String name);
}
