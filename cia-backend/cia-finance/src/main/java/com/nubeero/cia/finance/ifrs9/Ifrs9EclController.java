package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for the IFRS 9 Expected Credit Loss engine (Slice 3.5).
 *
 * <p>{@code POST /api/v1/finance/ifrs9/ecl/recognise} accepts an admin-supplied
 * batch of target ECL amounts (and optional stage transitions) for one
 * period. Posts JEs for the delta vs cumulative prior ECL.
 *
 * <p>RBAC: {@code FINANCE_APPROVE}.
 */
@RestController
@RequestMapping("/api/v1/finance/ifrs9/ecl")
@RequiredArgsConstructor
public class Ifrs9EclController {

    private final InvestmentEclEngine engine;

    @PostMapping("/recognise")
    @PreAuthorize("hasRole('FINANCE_APPROVE')")
    public ApiResponse<EclRecognitionResult> recognise(@Valid @RequestBody RecogniseEclRequest request) {
        List<InvestmentEclEngine.EclInput> inputs = request.ecls().stream()
            .map(e -> new InvestmentEclEngine.EclInput(e.holdingId(), e.eclAmount(), e.eclStage()))
            .toList();
        return ApiResponse.success(engine.recognise(request.periodId(), inputs));
    }
}
