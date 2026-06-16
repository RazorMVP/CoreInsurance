package com.nubeero.cia.compliance.dsar;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DsarPdfRendererTest {

    private final DsarPdfRenderer renderer = new DsarPdfRenderer(new HtmlToPdfConverter());

    @Test
    void rendersNonEmptyPdf() {
        DsarExport export = new DsarExport(Instant.parse("2026-06-15T00:00:00Z"),
                "id-1", "CUST-1",
                Map.of("customer_number", "CUST-1", "first_name", "Ada", "last_name", "Obi"),
                List.of(), List.of(), List.of(Map.of("policy_number", "POL-1", "net_premium", "47500")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        byte[] pdf = renderer.render(export);

        assertThat(pdf).isNotNull().isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }
}
