package com.nubeero.cia.common.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiMeta {
    private final long total;
    private final int page;
    private final int size;
    private final String nextCursor;
    private final String prevCursor;
}
