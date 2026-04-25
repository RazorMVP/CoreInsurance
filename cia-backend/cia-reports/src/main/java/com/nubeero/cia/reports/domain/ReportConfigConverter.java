package com.nubeero.cia.reports.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ReportConfigConverter implements AttributeConverter<ReportConfig, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(ReportConfig config) {
        if (config == null) return null;
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ReportConfig", e);
        }
    }

    @Override
    public ReportConfig convertToEntityAttribute(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, ReportConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize ReportConfig", e);
        }
    }
}
