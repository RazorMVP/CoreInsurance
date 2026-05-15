package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.CreateFiscalYearRequest;
import com.nubeero.cia.finance.dto.FiscalPeriodResponse;
import com.nubeero.cia.finance.dto.FiscalYearResponse;
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
@RequiredArgsConstructor
public class FiscalYearController {

    private final FiscalYearService service;

    @GetMapping
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<FiscalYearResponse>> list() {
        return ApiResponse.success(service.listAll());
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<FiscalYearResponse> active() {
        return ApiResponse.success(service.findActive());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<FiscalYearResponse> get(
        @PathVariable UUID id,
        @RequestParam(name = "includePeriods", defaultValue = "false") boolean includePeriods) {
        return ApiResponse.success(service.get(id, includePeriods));
    }

    @GetMapping("/{id}/periods")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<List<FiscalPeriodResponse>> listPeriods(@PathVariable UUID id) {
        return ApiResponse.success(service.listPeriods(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<FiscalYearResponse> create(@Valid @RequestBody CreateFiscalYearRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<FiscalYearResponse> activate(@PathVariable UUID id) {
        return ApiResponse.success(service.activate(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<FiscalYearResponse> close(@PathVariable UUID id) {
        return ApiResponse.success(service.close(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
