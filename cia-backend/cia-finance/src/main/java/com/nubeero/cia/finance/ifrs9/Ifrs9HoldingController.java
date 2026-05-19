package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for IFRS 9 investment-holding lifecycle (Slice 3.2).
 *
 * <ul>
 *   <li>{@code POST   /api/v1/finance/ifrs9/holdings} — register new holding;
 *       service runs §4.1 classification automatically.</li>
 *   <li>{@code POST   /api/v1/finance/ifrs9/holdings/{id}/reclassify} —
 *       apply §B4.1.26 reclassification (rare, audited).</li>
 *   <li>{@code GET    /api/v1/finance/ifrs9/holdings} — list all active holdings.</li>
 *   <li>{@code GET    /api/v1/finance/ifrs9/holdings/{id}} — holding detail.</li>
 * </ul>
 *
 * <p>RBAC: {@code FINANCE_APPROVE} for register + reclassify (creates GL
 * impact downstream); {@code FINANCE_VIEW} for reads.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/holdings")
@RequiredArgsConstructor
public class Ifrs9HoldingController {

    private final InvestmentClassificationService classificationService;
    private final InvestmentHoldingRepository holdingRepository;

    @PostMapping
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<InvestmentHoldingResponse> register(@Valid @RequestBody RegisterHoldingRequest request) {
        return ApiResponse.success(
            InvestmentHoldingResponse.from(classificationService.register(request)));
    }

    @PostMapping("/{holdingId}/reclassify")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<InvestmentHoldingResponse> reclassify(
            @PathVariable UUID holdingId,
            @Valid @RequestBody ReclassifyHoldingRequest request) {
        return ApiResponse.success(
            InvestmentHoldingResponse.from(classificationService.reclassify(holdingId, request)));
    }

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<InvestmentHoldingResponse>> list() {
        List<InvestmentHoldingResponse> list = holdingRepository
            .findByDeletedAtIsNullOrderBySecurityNameAsc()
            .stream()
            .map(InvestmentHoldingResponse::from)
            .toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/{holdingId}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<InvestmentHoldingResponse> get(@PathVariable UUID holdingId) {
        InvestmentHolding h = holdingRepository.findById(holdingId)
            .filter(x -> x.getDeletedAt() == null)
            .orElseThrow(() -> new InvestmentHoldingNotFoundException(holdingId));
        return ApiResponse.success(InvestmentHoldingResponse.from(h));
    }
}
