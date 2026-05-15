package com.nubeero.cia.finance.gl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nubeero.cia.common.api.ApiError;
import com.nubeero.cia.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Specialised handler for {@link PeriodLockedException}. Renders the
 * structured {@link LockDecision} payload so the frontend toast can show
 * the period label, current status, grace window, and the roles that
 * could unblock the write — all without a second API round-trip.
 *
 * <h2>Why not let {@code GlobalExceptionHandler} take it</h2>
 * <p>The global handler is generic by design: it knows about
 * {@code ApiError(code, message, field)} and nothing else. Stuffing the
 * lock payload into the {@code message} string is exactly the "stack-trace
 * concept leaking to UI" anti-pattern the expert critique called out. By
 * adding a dedicated advice scoped to the lock exception, the lock-specific
 * fields ride in {@code meta.periodLock} where the frontend can read them
 * by name.
 *
 * <h2>Ordering</h2>
 * <p>Spring resolves the most-specific {@code @ExceptionHandler} match —
 * {@code PeriodLockedException} extends {@code CiaException}, but the
 * narrower handler here wins over the broader one in {@code
 * GlobalExceptionHandler}. No explicit {@code @Order} needed.
 *
 * @since Module 12, Slice 1.7
 */
@Slf4j
@RestControllerAdvice
public class PeriodLockExceptionHandler {

    @ExceptionHandler(PeriodLockedException.class)
    public ResponseEntity<LockedApiResponse> handle(PeriodLockedException ex) {
        LockDecision d = ex.getDecision();
        log.info("PERIOD_LOCKED — period={} status={} reason={}",
            d != null ? d.periodLabel() : "(unknown)",
            d != null ? d.status() : "(unknown)",
            d != null ? d.reason() : ex.getMessage());

        ApiError error = new ApiError(ex.getErrorCode(), ex.getMessage(), null);
        LockedMeta meta = d == null ? null : new LockedMeta(
            d.periodId(), d.periodLabel(), d.status() != null ? d.status().name() : null,
            d.graceEndsAt(), d.overrideRoles());

        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(new LockedApiResponse(null, meta, List.of(error)));
    }

    /**
     * Top-level response envelope mirroring {@link ApiResponse} but with a
     * typed {@link LockedMeta} block instead of the generic {@code ApiMeta}.
     * Frontends key off {@code meta.periodLock != null} to render the
     * period-locked toast.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LockedApiResponse(
        Object data,
        LockedMeta meta,
        List<ApiError> errors
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LockedMeta(
        UUID periodId,
        String periodLabel,
        String status,
        Instant graceEndsAt,
        List<String> overrideRoles
    ) {}
}
