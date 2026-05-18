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
 * REST surface for the LRC recognition engine (Slice 2.3).
 *
 * <p>{@code POST /api/v1/finance/paa/lrc/recognise} runs the LRC engine
 * across every IFRS 17 group for the requested fiscal period. Idempotent
 * at the (group, period) grain — a re-run after partial completion picks
 * up only groups that haven't already been recognised, and the request
 * fails with 409 if any group has already been recognised for the period
 * (see {@link LrcRecognitionAlreadyDoneException}).
 *
 * <p>RBAC: {@code FINANCE_APPROVE} — same bar as period close + PPA. The
 * engine writes paa_lrc rows and posts journal entries via the
 * {@code JournalEntryService} gateway; both are non-reversible from this
 * endpoint and require the same level of authority.
 */
@RestController
@RequestMapping("/api/v1/finance/paa/lrc")
@RequiredArgsConstructor
public class PaaLrcController {

    private final LrcEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<LrcRecognitionResult> recognise(@Valid @RequestBody RecogniseLrcRequest request) {
        return ApiResponse.success(engine.recognise(request.periodId()));
    }

    public record RecogniseLrcRequest(@NotNull UUID periodId) {}
}
