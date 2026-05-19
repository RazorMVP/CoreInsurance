package com.nubeero.cia.finance.gl;

import java.util.List;

/**
 * Nested node DTO for the {@code GET /api/v1/finance/chart-of-accounts}
 * endpoint. The tree is rooted at the five account-type classes; children
 * are sorted ascending by {@code code}. Leaves carry empty {@code children}.
 */
public record ChartOfAccountNode(
    String code,
    String name,
    AccountType accountType,
    Ifrs17Role ifrs17Role,
    Ifrs9Role ifrs9Role,
    boolean active,
    List<ChartOfAccountNode> children
) {}
