package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.ReportField;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Renders report results as RFC 4180 CSV via StreamingResponseBody.
 * Never buffers the entire result set — streams row by row to the HTTP response.
 */
@Slf4j
@Component
public class ReportCsvRenderer {

    public StreamingResponseBody render(List<ReportField> columns,
                                        List<Map<String, Object>> rows) {
        return outputStream -> {
            Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            // BOM for Excel UTF-8 compatibility
            writer.write('﻿');

            String[] headers = columns.stream()
                    .map(ReportField::getLabel)
                    .toArray(String[]::new);

            String[] keys = columns.stream()
                    .map(ReportField::getKey)
                    .toArray(String[]::new);

            try (CSVPrinter printer = new CSVPrinter(writer,
                    CSVFormat.DEFAULT.builder().setHeader(headers).build())) {

                for (Map<String, Object> row : rows) {
                    Object[] values = new Object[keys.length];
                    for (int i = 0; i < keys.length; i++) {
                        Object v = row.get(keys[i]);
                        values[i] = v != null ? v.toString() : "";
                    }
                    printer.printRecord(values);
                }
            } catch (Exception e) {
                log.error("CSV rendering failed", e);
            }
        };
    }
}
