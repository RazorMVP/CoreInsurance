package com.nubeero.cia.finance.ifrs9;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentHoldingRepository extends JpaRepository<InvestmentHolding, UUID> {

    Optional<InvestmentHolding> findByIsinAndDeletedAtIsNull(String isin);

    List<InvestmentHolding> findByDeletedAtIsNullOrderBySecurityNameAsc();

    List<InvestmentHolding> findByStatusAndDeletedAtIsNullOrderBySecurityNameAsc(HoldingStatus status);

    List<InvestmentHolding> findByClassificationAndDeletedAtIsNullOrderBySecurityNameAsc(InvestmentClassification classification);

    List<InvestmentHolding> findByAssetTypeAndDeletedAtIsNullOrderBySecurityNameAsc(AssetType assetType);
}
