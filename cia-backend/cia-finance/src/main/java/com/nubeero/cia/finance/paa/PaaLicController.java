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
 * REST surface for the LIC recognition engine (Slice 2.4).
 *
 * <p>{@code POST /api/v1/finance/paa/lic/recognise} runs the LIC engine
 * across every IFRS 17 group for the requested fiscal period. Idempotent
 * at the (group, period) grain — fails with 409 if any group has already
 * been recognised for the period (see
 * {@link LicRecognitionAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as period close, PPA, and LRC
 * recognition. The engine writes paa_lic disclosure rows but posts no JE
 * in v1 (see {@link LicEngine} class javadoc).
 */
@RestController
@RequestMapping("/api/v1/finance/paa/lic")
@RequiredArgsConstructor
public class PaaLicController {

    private final LicEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<LicRecognitionResult> recognise(@Valid @RequestBody RecogniseLicRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseLicRequest(@NotNull UUID periodId) {}
}
