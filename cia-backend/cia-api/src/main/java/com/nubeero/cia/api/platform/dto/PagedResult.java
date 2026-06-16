package com.nubeero.cia.api.platform.dto;

import java.util.List;

/** A page of results plus the metadata the controller maps into {@code ApiMeta}. */
public record PagedResult<T>(List<T> items, long total, int page, int size) {}
