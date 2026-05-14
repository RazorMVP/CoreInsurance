package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.dto.ReverseJournalEntryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
@RequiredArgsConstructor
public class JournalEntryController {

    private final JournalEntryService service;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('FINANCE_VIEW')")
    public ApiResponse<JournalEntryResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE_CREATE')")
    public ApiResponse<JournalEntryResponse> post(@Valid @RequestBody PostJournalEntryRequest request) {
        return ApiResponse.success(service.post(request));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<JournalEntryResponse> reverse(
        @PathVariable UUID id,
        @Valid @RequestBody ReverseJournalEntryRequest request) {
        return ApiResponse.success(service.reverse(id, request.reason()));
    }
}
