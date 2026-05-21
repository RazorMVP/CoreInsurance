package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.JournalEntrySummaryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.dto.PriorPeriodAdjustmentRequest;
import com.nubeero.cia.finance.dto.ReverseJournalEntryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Journal entry REST endpoints. Slice 1.4 exposes:
 *
 * <ul>
 *   <li>{@code POST   /api/v1/finance/journal-entries} — post a manual JE.
 *       Returns 201 with the saved entry plus its lines.</li>
 *   <li>{@code GET    /api/v1/finance/journal-entries/{id}} — read by id.</li>
 *   <li>{@code POST   /api/v1/finance/journal-entries/{id}/reverse} — record
 *       a mirror posting and flip the original to {@code REVERSED}. Returns
 *       200 with the reversal entry.</li>
 * </ul>
 *
 * <p>RBAC: posting requires {@code FINANCE_CREATE}; reads require
 * {@code FINANCE_VIEW}; reversal requires {@code FINANCE_APPROVE} —
 * higher-bar because reversal materially changes the GL.
 */
@RestController
@RequestMapping("/api/v1/finance/journal-entries")
@Tag(name = "Journal Entries",
     description = "The Slice 1.4 GL gateway. Every JE in the system passes through JournalEntryService — subledger posting, IFRS 17 PAA engines, IFRS 9 measurement engines, NAICOM source data, manual posts. Idempotency triple: (source_module, source_event_type, source_reference).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class JournalEntryController {

    private final JournalEntryService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Search / list journal entries (paginated)",
               description = "All filters optional. Filtering by accountCode or classOfBusinessId joins to journal_entry_line — a JE with N matching lines still appears once in the result (DISTINCT). Lines are NOT included in the summary; drill into GET /{id} for the line array. The Slice 1.10 classOfBusinessId substrate is filterable here.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Journal entries page",
            content = @Content(schema = @Schema(implementation = JournalEntrySummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<JournalEntrySummaryResponse>> list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessTo,
        @RequestParam(required = false) UUID periodId,
        @RequestParam(required = false) String sourceModule,
        @RequestParam(required = false) JournalEntryStatus status,
        @RequestParam(required = false) String accountCode,
        @RequestParam(required = false) UUID classOfBusinessId,
        @PageableDefault(size = 20, sort = "businessDate", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<JournalEntrySummaryResponse> page = service.list(
            businessFrom, businessTo, periodId, sourceModule, status,
            accountCode, classOfBusinessId, pageable
        );
        ApiMeta meta = ApiMeta.builder()
            .total(page.getTotalElements())
            .page(page.getNumber())
            .size(page.getSize())
            .build();
        return ApiResponse.success(page.getContent(), meta);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get a journal entry by id",
               description = "Returns the JE header plus its lines. JE narratives sometimes embed disclosure substrate (e.g. premium-receivable ECL provision matrix for §B5.5.36 evidence) — see /finance/ifrs9/premium-receivable-ecl docs.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Journal entry found",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Journal entry not found", content = @Content)
    })
    public ApiResponse<JournalEntryResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    @Operation(summary = "Post a manual journal entry",
               description = "Manual JE post for adjustments that do not originate from a subledger event. Validates double-entry balance (sum debits == sum credits per currency), checks against period locks (HTTP 423 LOCKED if rejected), and applies the (source_module, source_event_type, source_reference) UNIQUE constraint for idempotency.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Journal entry posted",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Unbalanced lines, invalid account, or missing required fields", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_CREATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "JournalEntryDuplicateException — idempotency triple already used", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "PeriodLockedException — target period is closed", content = @Content)
    })
    public ApiResponse<JournalEntryResponse> post(@Valid @RequestBody PostJournalEntryRequest request) {
        return ApiResponse.success(service.post(request));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Reverse a previously posted JE",
               description = "Posts a mirror JE (debits ↔ credits) and flips the original to REVERSED. The reversal carve-out in LockableByPeriod.isReversal() means reversals CAN cross a closed period — corrections to closed periods remain possible. Requires the elevated FINANCE_APPROVE role.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reversal entry created",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Reason missing", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Journal entry not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Already reversed, or status not POSTED", content = @Content)
    })
    public ApiResponse<JournalEntryResponse> reverse(
        @PathVariable UUID id,
        @Valid @RequestBody ReverseJournalEntryRequest request) {
        return ApiResponse.success(service.reverse(id, request.reason()));
    }

    @PostMapping("/prior-period-adjustment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_APPROVE_PPA')")
    @Operation(summary = "Post an IAS-8 Prior-Period Adjustment",
               description = "Slice 1.7c. Posts a restatement JE that legitimately crosses a closed period to correct an error or accounting policy change. Gated by FINANCE_APPROVE_PPA (distinct from FINANCE_CREATE) to enforce segregation of duties — the officer who booked the original cannot approve its restatement. Notifies recipients via PeriodReopenedEvent.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "PPA posted",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (unbalanced, missing reason, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE_PPA", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Target period not eligible for PPA", content = @Content)
    })
    public ApiResponse<JournalEntryResponse> postPriorPeriodAdjustment(
        @Valid @RequestBody PriorPeriodAdjustmentRequest request) {
        return ApiResponse.success(service.postPriorPeriodAdjustment(request));
    }
}
