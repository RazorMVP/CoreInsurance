package com.nubeero.cia.setup.policy.dto;

import com.nubeero.cia.setup.policy.ClauseApplicability;
import com.nubeero.cia.setup.policy.ClauseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ClauseRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String text;

    @NotNull
    private ClauseType type;

    @NotNull
    private ClauseApplicability applicability;

    /** Product UUIDs this clause applies to; empty/null = applies to all products. */
    private List<String> productIds = new ArrayList<>();
}
