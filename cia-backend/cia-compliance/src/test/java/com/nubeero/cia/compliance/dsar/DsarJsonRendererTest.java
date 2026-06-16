package com.nubeero.cia.compliance.dsar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DsarJsonRendererTest {

    private final DsarJsonRenderer renderer = new DsarJsonRenderer();

    @Test
    void rendersStructuredJsonWithDecryptedPii() {
        DsarExport export = new DsarExport(Instant.parse("2026-06-15T00:00:00Z"),
                "id-1", "CUST-1",
                Map.of("customer_number", "CUST-1", "id_number", "NIN123", "address", "12 Marina"),
                List.of(Map.of("first_name", "Bola", "id_number", "NIN999")),
                List.of(), List.of(Map.of("policy_number", "POL-1")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        byte[] json = renderer.render(export);
        String s = new String(json);

        assertThat(s).contains("CUST-1").contains("NIN123").contains("12 Marina")
                .contains("POL-1").contains("\"directors\"").contains("\"policies\"");
    }
}
