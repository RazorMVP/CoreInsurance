package com.nubeero.cia.finance.ifrs9;

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
 * REST surface for the IFRS 9 effective-interest-method engine (Slice 3.3).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/amortised-cost/recognise} runs the
 * engine across every eligible AC + FVOCI_DEBT holding for the requested
 * fiscal period. Idempotent at the (holding, period) grain — fails with
 * 409 if any holding has already been recognised for the period (see
 * {@link AmortisedCostAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as the PAA engines.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/amortised-cost")
@RequiredArgsConstructor
public class Ifrs9AmortisedCostController {

    private final AmortisedCostEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<AmortisedCostResult> recognise(@Valid @RequestBody RecogniseAmortisedCostRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseAmortisedCostRequest(@NotNull UUID periodId) {}
}
