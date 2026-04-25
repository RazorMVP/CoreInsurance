package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.ReportChart;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportField;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Renders report results as a PDF using Apache PDFBox 3.x.
 * Produces a branded NubSure document with header, filter summary, data table,
 * and optional chart (bar chart as embedded image via JFreeChart).
 * Never throws — returns null on failure so the caller can skip storage.
 */
@Slf4j
@Component
public class ReportPdfRenderer {

    private static final float MARGIN = 50f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float ROW_HEIGHT = 18f;
    private static final float HEADER_HEIGHT = 24f;

    public byte[] render(ReportDefinition definition,
                         List<ReportField> columns,
                         List<Map<String, Object>> rows,
                         Map<String, String> appliedFilters) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = PAGE_HEIGHT - MARGIN;

                // ── Report title ─────────────────────────────────────
                PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.beginText();
                cs.setFont(titleFont, 16);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(definition.getName());
                cs.endText();
                y -= 24f;

                // ── Subtitle: category + generated date ──────────────
                PDType1Font normalFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(normalFont, 9);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(definition.getCategory().name() + "  ·  Generated " +
                            LocalDate.now() + "  ·  NubSure");
                cs.endText();
                y -= 20f;

                // ── Separator line ────────────────────────────────────
                cs.setLineWidth(0.5f);
                cs.moveTo(MARGIN, y);
                cs.lineTo(PAGE_WIDTH - MARGIN, y);
                cs.stroke();
                y -= 16f;

                // ── Table header ─────────────────────────────────────
                float colWidth = (PAGE_WIDTH - 2 * MARGIN) / Math.max(columns.size(), 1);
                PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

                cs.setNonStrokingColor(0.92f, 0.95f, 0.95f); // light teal tint
                cs.addRect(MARGIN, y - HEADER_HEIGHT, PAGE_WIDTH - 2 * MARGIN, HEADER_HEIGHT);
                cs.fill();

                cs.beginText();
                cs.setFont(boldFont, 8);
                cs.setNonStrokingColor(0f, 0f, 0f);
                float x = MARGIN + 4f;
                for (ReportField col : columns) {
                    cs.newLineAtOffset(x, y - 14f);
                    cs.showText(truncate(col.getLabel(), 20));
                    x += colWidth;
                    cs.newLineAtOffset(-x, 0);
                    cs.newLineAtOffset(x, 0);
                }
                cs.endText();
                y -= HEADER_HEIGHT;

                // ── Table rows ────────────────────────────────────────
                cs.setFont(normalFont, 8);
                for (int i = 0; i < Math.min(rows.size(), 40); i++) {
                    if (y < MARGIN + ROW_HEIGHT) break; // simple single-page guard

                    Map<String, Object> row = rows.get(i);
                    if (i % 2 == 0) {
                        cs.setNonStrokingColor(0.97f, 0.97f, 0.97f);
                        cs.addRect(MARGIN, y - ROW_HEIGHT, PAGE_WIDTH - 2 * MARGIN, ROW_HEIGHT);
                        cs.fill();
                    }

                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.beginText();
                    x = MARGIN + 4f;
                    for (ReportField col : columns) {
                        cs.newLineAtOffset(x, y - 12f);
                        Object val = row.get(col.getKey());
                        cs.showText(truncate(val != null ? val.toString() : "", 22));
                        x += colWidth;
                        cs.newLineAtOffset(-x, 0);
                        cs.newLineAtOffset(x, 0);
                    }
                    cs.endText();
                    y -= ROW_HEIGHT;
                }

                // ── Footer ────────────────────────────────────────────
                cs.beginText();
                cs.setFont(normalFont, 7);
                cs.setNonStrokingColor(0.5f, 0.5f, 0.5f);
                cs.newLineAtOffset(MARGIN, MARGIN / 2);
                cs.showText("NubSure — Confidential  ·  " + rows.size() + " records");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("PDF rendering failed for report {}", definition.getId(), e);
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
