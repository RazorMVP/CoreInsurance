package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Renders a {@link DsarExport} to a human-readable PDF via the shared NotoSans-embedded HTML→PDF converter. */
@Component
@RequiredArgsConstructor
public class DsarPdfRenderer {

    private final HtmlToPdfConverter converter;

    public byte[] render(DsarExport export) {
        StringBuilder html = new StringBuilder("<html><body>");
        html.append("<h1>Data Subject Access Request</h1>");
        html.append("<p>Customer: ").append(esc(export.customerNumber()))
            .append(" &nbsp; Generated: ").append(esc(String.valueOf(export.generatedAt())))
            .append("</p>");
        section(html, "Customer", List.of(export.customer()));
        section(html, "Directors", export.directors());
        section(html, "Documents", export.documents());
        section(html, "Policies", export.policies());
        section(html, "Quotes", export.quotes());
        section(html, "Claims", export.claims());
        section(html, "Endorsements", export.endorsements());
        section(html, "Debit Notes", export.debitNotes());
        section(html, "Receipts", export.receipts());
        section(html, "Credit Notes", export.creditNotes());
        section(html, "Payments", export.payments());
        section(html, "Audit History", export.auditHistory());
        html.append("</body></html>");
        try {
            return converter.convert(html.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to render DSAR PDF", e);
        }
    }

    private void section(StringBuilder html, String title, List<Map<String, Object>> rows) {
        html.append("<h2>").append(esc(title)).append("</h2>");
        if (rows == null || rows.isEmpty()) {
            html.append("<p>(none)</p>");
            return;
        }
        for (Map<String, Object> row : rows) {
            html.append("<p>");
            row.forEach((k, v) -> html.append("<b>").append(esc(k)).append(":</b> ")
                    .append(esc(String.valueOf(v))).append(" &nbsp; "));
            html.append("</p>");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
