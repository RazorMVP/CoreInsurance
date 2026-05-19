package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the IFRS 9 §5.5.15 premium-receivable ECL engine
 * (Slice 3.6).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/premium-receivable-ecl/recognise}
 * accepts an admin-supplied provision matrix (aging buckets × default
 * rates), computes the lifetime ECL, and posts the JE for the movement
 * versus cumulative prior ECL.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/premium-receivable-ecl")
@RequiredArgsConstructor
public class Ifrs9PremiumReceivableEclController {

    private final PremiumReceivableEclEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<PremiumReceivableEclResult> recognise(
            @Valid @RequestBody RecognisePremiumReceivableEclRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId(), request.agingBuckets()));
    }
}
