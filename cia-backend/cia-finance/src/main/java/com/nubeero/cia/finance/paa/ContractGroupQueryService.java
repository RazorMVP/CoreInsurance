package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.paa.dto.ContractGroupSummaryResponse;
import com.nubeero.cia.finance.paa.dto.PortfolioSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only query service exposing IFRS 17 portfolio + contract-group
 * master data to the Phase 5 frontend (slice F5.11). All writers live in
 * {@code ContractGroupingService} (Slice 2.2) — this class is purely
 * projection.
 *
 * <p>RBAC: callers must hold {@code FINANCE_VIEW}; gating happens at the
 * controller layer.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractGroupQueryService {

    private final GroupOfContractsRepository groupRepository;
    private final PortfolioRepository portfolioRepository;

    public List<ContractGroupSummaryResponse> listGroups(
        UUID portfolioId,
        Integer cohortYear,
        Onerousness onerousness,
        GroupStatus status
    ) {
        return groupRepository.search(portfolioId, cohortYear, onerousness, status).stream()
            .map(this::toSummary)
            .toList();
    }

    public List<PortfolioSummaryResponse> listPortfolios() {
        return portfolioRepository.findByDeletedAtIsNullOrderByCodeAsc().stream()
            .map(this::toPortfolioSummary)
            .toList();
    }

    private ContractGroupSummaryResponse toSummary(GroupOfContracts g) {
        Portfolio p = g.getPortfolio();
        return new ContractGroupSummaryResponse(
            g.getId(),
            p.getId(),
            p.getCode(),
            p.getName(),
            p.getContractNature(),
            g.getCohortYear(),
            g.getOnerousness(),
            g.getStatus(),
            g.getCreatedAt()
        );
    }

    private PortfolioSummaryResponse toPortfolioSummary(Portfolio p) {
        return new PortfolioSummaryResponse(
            p.getId(),
            p.getCode(),
            p.getName(),
            p.getClassOfBusinessId(),
            p.getDescription(),
            p.isActive()
        );
    }
}
