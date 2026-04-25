package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SearchResultDto {
    String id;
    String type;        // "Policy" | "Claim" | "Customer" | "Quote"
    String label;       // primary text: policy number, claim number, customer name
    String sub;         // secondary text: status, class, etc.
    String path;        // frontend route e.g. "/policies/uuid"
}
