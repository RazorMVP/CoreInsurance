package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the IFRS 17 §47-49 onerous contract test (Slice 2.7).
 *
 * <p>{@code POST /api/v1/finance/paa/onerous-test/run} runs the onerous
 * test for every paa_lrc row in the requested period, posting loss-
 * component increase or reversal JEs as needed. Naturally idempotent —
 * a re-run with no underlying movement produces a no-op result.
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as other PAA period-close
 * engines (LRC, LIC, DiscountUnwind).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/onerous-test")
@RequiredArgsConstructor
public class PaaOnerousTestController {

    private final OnerousContractTestEngine engine;

    @PostMapping("/run")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<OnerousTestResult> run(@Valid @RequestBody OnerousTestRequest request) {
        return ApiResponse.success(engine.test(request.periodId()));
    }

    public record OnerousTestRequest(@NotNull UUID periodId) {}
}
