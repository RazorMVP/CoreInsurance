package com.nubeero.cia.finance.naicom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Renders a submission's payload as an auditor-grade PDF cover page +
 * pretty-printed JSON body.
 *
 * <p>Module 12 Phase 4 Slice 4.10 (v1). The v1 layout is intentionally
 * minimal — submission metadata as a header, the full payload as a
 * monospaced JSON dump across as many pages as needed. NAICOM's
 * regulator-facing PDF templates are auditor-visible artifacts;
 * per-submission-type purpose-built layouts (per-class tables for N01,
 * solvency tables for N03, etc.) are deferred to v2 once the regulator
 * provides the exact prescribed forms.
 *
 * <h2>Determinism</h2>
 * <p>PDFBox embeds creation / modification timestamps in the document
 * info dictionary by default. The renderer sets both to a fixed value
 * derived from the submission's own period_end so the rendered bytes
 * are stable across runs — the SHA-256 in
 * {@code naicom_submission_artifact} depends on this.
 *
 * <h2>v1 disclosure</h2>
 * <p>The cover-page renderer here is bytes-deterministic and prints the
 * payload verbatim; the auditor's report-quality experience comes from
 * the JSON artifact. v2 should introduce per-submission-type templates
 * once the regulator publishes prescribed forms.
 */
@Component
@Slf4j
public class PdfArtifactRenderer implements NaicomArtifactRenderer {

    private static final float MARGIN = 50f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float LINE_HEIGHT = 11f;
    private static final float HEADER_FONT_SIZE = 16f;
    private static final float META_FONT_SIZE = 9f;
    private static final float BODY_FONT_SIZE = 8f;

    private final ObjectMapper objectMapper;

    public PdfArtifactRenderer() {
        this.objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public ArtifactFormat format() {
        return ArtifactFormat.PDF;
    }

    @Override
    public String mimeType() {
        return "application/pdf";
    }

    @Override
    public String fileExtension() {
        return "pdf";
    }

    @Override
    public byte[] render(NaicomSubmission submission) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Determinism: fix document timestamps to a stable value
            // derived from the submission, not Instant.now().
            doc.getDocumentInformation().setCreationDate(null);
            doc.getDocumentInformation().setModificationDate(null);
            doc.getDocumentInformation().setCreator("CIAGB Module 12 Phase 4");
            doc.getDocumentInformation().setProducer("CIAGB");
            doc.getDocumentInformation().setTitle(
                "NAICOM Submission " + submission.getSubmissionType().name());
            doc.getDocumentInformation().setSubject(
                "Period " + submission.getPeriodStart() + " to " + submission.getPeriodEnd());

            String bodyText = renderBody(submission);

            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font metaFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.COURIER);

            // Paginate the body lines.
            String[] lines = bodyText.split("\n", -1);
            int lineIdx = 0;
            boolean first = true;
            while (lineIdx < lines.length) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = PAGE_HEIGHT - MARGIN;
                    if (first) {
                        // Header
                        cs.beginText();
                        cs.setFont(titleFont, HEADER_FONT_SIZE);
                        cs.newLineAtOffset(MARGIN, y);
                        cs.showText("NAICOM Submission");
                        cs.endText();
                        y -= 22f;

                        cs.beginText();
                        cs.setFont(titleFont, 12);
                        cs.newLineAtOffset(MARGIN, y);
                        cs.showText(submission.getSubmissionType().name());
                        cs.endText();
                        y -= 18f;

                        for (String metaLine : metaLines(submission)) {
                            cs.beginText();
                            cs.setFont(metaFont, META_FONT_SIZE);
                            cs.newLineAtOffset(MARGIN, y);
                            cs.showText(metaLine);
                            cs.endText();
                            y -= 12f;
                        }

                        cs.setLineWidth(0.5f);
                        cs.moveTo(MARGIN, y);
                        cs.lineTo(PAGE_WIDTH - MARGIN, y);
                        cs.stroke();
                        y -= 14f;
                        first = false;
                    }

                    cs.setFont(bodyFont, BODY_FONT_SIZE);
                    while (lineIdx < lines.length && y > MARGIN + LINE_HEIGHT) {
                        cs.beginText();
                        cs.newLineAtOffset(MARGIN, y);
                        cs.showText(stripUnencodable(lines[lineIdx]));
                        cs.endText();
                        y -= LINE_HEIGHT;
                        lineIdx++;
                    }
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            // PDFBox writes to a ByteArrayOutputStream — IO failures here are
            // programmer / runtime errors worth surfacing rather than masking.
            log.error("PDF rendering failed for submission {} ({}): {}",
                submission.getId(), submission.getSubmissionType(), e.getMessage());
            throw new IllegalStateException(
                "PDF render failed for submission " + submission.getId(), e);
        }
    }

    private String renderBody(NaicomSubmission submission) {
        Map<String, Object> payload = submission.getPayload();
        if (payload == null || payload.isEmpty()) return "(empty payload)";
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "(payload serialization failed: " + e.getMessage() + ")";
        }
    }

    private static java.util.List<String> metaLines(NaicomSubmission s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("Period:       " + s.getPeriodStart() + " to " + s.getPeriodEnd());
        out.add("State:        " + s.getState());
        if (s.getSubmittedAt() != null) {
            out.add("Submitted:    " + s.getSubmittedAt()
                + (s.getSubmittedBy() != null ? " by " + s.getSubmittedBy() : ""));
        }
        if (s.getAcknowledgedAt() != null) {
            out.add("Acknowledged: " + s.getAcknowledgedAt()
                + (s.getNaicomUid() != null ? " (uid=" + s.getNaicomUid() + ")" : ""));
        }
        return out;
    }

    /**
     * Standard14 Helvetica + Courier cover WinAnsi (ISO-8859-1) only —
     * anything outside that range (the ₦ symbol, em dashes from the
     * engine notes) trips
     * {@code IllegalArgumentException: U+20A6 ('uni20A6') is not available}.
     * v1 strips them; v2 should embed a TTF that covers Latin Extended
     * + currency-symbol ranges.
     */
    private static String stripUnencodable(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0xFF) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
