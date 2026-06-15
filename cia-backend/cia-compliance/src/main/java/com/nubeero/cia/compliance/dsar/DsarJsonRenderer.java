package com.nubeero.cia.compliance.dsar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/** Serializes a {@link DsarExport} to pretty, machine-readable JSON (the NDPR data-portability copy). */
@Component
public class DsarJsonRenderer {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public byte[] render(DsarExport export) {
        try {
            return mapper.writeValueAsBytes(export);
        } catch (Exception e) {
            throw new RuntimeException("Failed to render DSAR JSON", e);
        }
    }
}
