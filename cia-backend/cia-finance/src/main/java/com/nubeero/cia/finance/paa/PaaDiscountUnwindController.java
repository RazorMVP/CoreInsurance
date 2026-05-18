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
 * REST surface for the IFRS 17 §87-92 discount unwind engine (Slice 2.6).
 *
 * <p>{@code POST /api/v1/finance/paa/finance-unwind/recognise} computes
 * discount unwind for every paa_lic row in the requested period, posts
 * the JE (P&amp;L or OCI per tenant election), and updates the paa_lic row.
 * No-op for tenants where {@code paa_config.discount_lic = FALSE}.
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as the other PAA period-close
 * operations.
 */
@RestController
@RequestMapping("/api/v1/finance/paa/finance-unwind")
@RequiredArgsConstructor
public class PaaDiscountUnwindController {

    private final DiscountUnwindEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<DiscountUnwindResult> recognise(@Valid @RequestBody RecogniseUnwindRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseUnwindRequest(@NotNull UUID periodId) {}
}
