package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.CreateFiscalYearRequest;
import com.nubeero.cia.finance.dto.FiscalPeriodResponse;
import com.nubeero.cia.finance.dto.FiscalYearResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the fiscal-year lifecycle (Slice 1.6, d6).
 *
 * <ul>
 *   <li>{@code GET    /api/v1/finance/fiscal-years} — list (no period embed).</li>
 *   <li>{@code GET    /api/v1/finance/fiscal-years/{id}?includePeriods=true} — single.</li>
 *   <li>{@code GET    /api/v1/finance/fiscal-years/active} — the current FY.</li>
 *   <li>{@code GET    /api/v1/finance/fiscal-years/{id}/periods} — child periods only.</li>
 *   <li>{@code POST   /api/v1/finance/fiscal-years} — create + auto-generate periods.</li>
 *   <li>{@code POST   /api/v1/finance/fiscal-years/{id}/activate} — flip to ACTIVE.</li>
 *   <li>{@code POST   /api/v1/finance/fiscal-years/{id}/close} — flip to CLOSED.</li>
 *   <li>{@code DELETE /api/v1/finance/fiscal-years/{id}} — soft delete (rejected if any JE references its periods).</li>
 * </ul>
 *
 * <p>RBAC: reads = {@code FINANCE_VIEW}; create / activate / close /
 * delete = {@code FINANCE_APPROVE} (matches the same higher-bar treatment
 * Slice 1.4's reverse endpoint received — fiscal-year state changes
 * cascade to every downstream slice).
 */
@RestController
@RequestMapping("/api/v1/finance/fiscal-years")
@Tag(name = "Fiscal Years",
     description = "Fiscal-year lifecycle and the period tree under each year. State machine: DRAFT → ACTIVE → CLOSED. Creating a fiscal year auto-generates MONTH / QUARTER / HALF_YEAR / YEAR periods (19 rows) via FiscalYearService — see Slice 1.6.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class FiscalYearController {

    private final FiscalYearService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List all fiscal years (no period embedding)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fiscal years (ordered by start date)",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_VIEW", content = @Content)
    })
    public ApiResponse<List<FiscalYearResponse>> list() {
        return ApiResponse.success(service.listAll());
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get the currently ACTIVE fiscal year",
               description = "Exactly one fiscal year is ACTIVE per tenant. Returns 404 if no year has been activated yet (initial tenant setup).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active fiscal year",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No fiscal year is currently ACTIVE", content = @Content)
    })
    public ApiResponse<FiscalYearResponse> active() {
        return ApiResponse.success(service.findActive());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "Get a fiscal year by id",
               description = "Set includePeriods=true to embed the period tree (MONTH → QUARTER → HALF_YEAR → YEAR) inline.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fiscal year found",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fiscal year not found", content = @Content)
    })
    public ApiResponse<FiscalYearResponse> get(
        @PathVariable UUID id,
        @RequestParam(name = "includePeriods", defaultValue = "false") boolean includePeriods) {
        return ApiResponse.success(service.get(id, includePeriods));
    }

    @GetMapping("/{id}/periods")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    @Operation(summary = "List periods under a fiscal year",
               description = "Returns every MONTH / QUARTER / HALF_YEAR / YEAR period that belongs to this fiscal year (19 rows total, generated eagerly at fiscal-year create).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Period list",
            content = @Content(schema = @Schema(implementation = FiscalPeriodResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fiscal year not found", content = @Content)
    })
    public ApiResponse<List<FiscalPeriodResponse>> listPeriods(@PathVariable UUID id) {
        return ApiResponse.success(service.listPeriods(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Create a fiscal year + auto-generate periods",
               description = "Creates the fiscal year in DRAFT state and synchronously generates all 19 MONTH / QUARTER / HALF_YEAR / YEAR periods. Activation is a separate step.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Fiscal year + periods created",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error (overlapping dates, invalid name, etc.)", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Fiscal year with overlapping start/end dates already exists", content = @Content)
    })
    public ApiResponse<FiscalYearResponse> create(@Valid @RequestBody CreateFiscalYearRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Transition DRAFT → ACTIVE",
               description = "Demotes any currently ACTIVE fiscal year to CLOSED and activates this one. There is always exactly one ACTIVE fiscal year per tenant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fiscal year activated",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fiscal year not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot activate a CLOSED year", content = @Content)
    })
    public ApiResponse<FiscalYearResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(service.activate(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Transition ACTIVE → CLOSED",
               description = "Final state. Year-end close cascades hard-close to all child periods that are still OPEN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fiscal year closed",
            content = @Content(schema = @Schema(implementation = FiscalYearResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fiscal year not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Year is already CLOSED, or not ACTIVE", content = @Content)
    })
    public ApiResponse<FiscalYearResponse> close(@PathVariable UUID id) {
        return ApiResponse.success(service.close(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    @Operation(summary = "Soft-delete a fiscal year",
               description = "Sets deleted_at. Rejected with 409 if any journal entry references one of this year's periods — historical GL data cannot be orphaned.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Fiscal year soft-deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks FINANCE_APPROVE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fiscal year not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Cannot delete — journal entries reference this year's periods", content = @Content)
    })
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
