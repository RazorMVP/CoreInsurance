package com.nubeero.cia.quotation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stored as JSONB inside quote_risks.loadings / quote_risks.discounts
 * and quotes.quote_loadings / quotes.quote_discounts.
 *
 * typeName is denormalized at save time so the PDF can render it without a join.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdjustmentEntry {
    private UUID             typeId;
    private String           typeName;
    private AdjustmentFormat format;
    private BigDecimal       value;
}
