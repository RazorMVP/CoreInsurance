package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for the IFRS 9 fair-value remeasurement engine (Slice 3.4).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/fair-value/recognise} accepts an
 * admin-supplied list of {@code (holdingId, fairValue)} pairs for one
 * period and posts the appropriate JEs (P&amp;L for FVPL, OCI reserve for
 * FVOCI debt/equity). Idempotent — re-runs that find
 * {@code closing_fair_value} already set on a holding's carrying-value
 * row skip silently per IFRS 9 §5.7's "fair value once" semantic.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/fair-value")
@RequiredArgsConstructor
public class Ifrs9FairValueController {

    private final FairValueEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<FairValueResult> recognise(@Valid @RequestBody RecogniseFairValuesRequest request) {
        // Preserve request order in the engine's iteration via LinkedHashMap
        // — keeps disclosure entries reproducible regardless of HashMap
        // hash-order drift across JVM versions.
        Map<UUID, BigDecimal> valuations = new LinkedHashMap<>();
        for (RecogniseFairValuesRequest.HoldingValuation v : request.valuations()) {
            valuations.put(v.holdingId(), v.fairValue());
        }
        return ApiResponse.success(engine.recognise(request.periodId(), valuations));
    }
}
