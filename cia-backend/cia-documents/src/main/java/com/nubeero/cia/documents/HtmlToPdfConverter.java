package com.nubeero.cia.documents;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class HtmlToPdfConverter {

    private static final float PAGE_WIDTH   = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT  = PDRectangle.A4.getHeight();
    private static final float MARGIN       = 60f;
    private static final float CONTENT_W    = PAGE_WIDTH - 2 * MARGIN;

    private static final int   SIZE_BODY    = 10;
    private static final int   SIZE_H1      = 18;
    private static final int   SIZE_H2      = 14;
    private static final int   SIZE_H3      = 12;
    private static final float LH_BODY      = 14f;
    private static final float LH_H1        = 26f;
    private static final float LH_H2        = 20f;
    private static final float LH_H3        = 16f;

    public byte[] convert(String html) throws IOException {
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold    = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            RenderState state   = new RenderState(doc, regular, bold);

            for (Node child : jsoupDoc.body().childNodes()) {
                renderNode(state, child);
            }
            state.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ─── Node dispatch ────────────────────────────────────────────────────

    private void renderNode(RenderState s, Node node) throws IOException {
        if (node instanceof TextNode tn) {
            String text = tn.text().trim();
            if (!text.isEmpty()) s.writeText(text, SIZE_BODY, false, LH_BODY);
        } else if (node instanceof Element el) {
            renderElement(s, el);
        }
    }

    private void renderElement(RenderState s, Element el) throws IOException {
        switch (el.tagName().toLowerCase()) {
            case "h1" -> {
                s.vSpace(10f);
                s.writeText(el.text(), SIZE_H1, true, LH_H1);
                s.vSpace(6f);
            }
            case "h2" -> {
                s.vSpace(8f);
                s.writeText(el.text(), SIZE_H2, true, LH_H2);
                s.vSpace(4f);
            }
            case "h3" -> {
                s.vSpace(6f);
                s.writeText(el.text(), SIZE_H3, true, LH_H3);
                s.vSpace(2f);
            }
            case "p" -> {
                if (!el.text().isBlank()) {
                    s.writeText(el.text(), SIZE_BODY, false, LH_BODY);
                    s.vSpace(4f);
                }
            }
            case "b", "strong" ->
                s.writeText(el.text(), SIZE_BODY, true, LH_BODY);
            case "br" -> s.vSpace(LH_BODY);
            case "hr" -> {
                s.vSpace(4f);
                s.drawLine();
                s.vSpace(4f);
            }
            case "ul" -> {
                for (Element li : el.children()) {
                    if ("li".equalsIgnoreCase(li.tagName()))
                        s.writeText("\u2022 " + li.text(), SIZE_BODY, false, LH_BODY);
                }
                s.vSpace(4f);
            }
            case "ol" -> {
                int n = 1;
                for (Element li : el.children()) {
                    if ("li".equalsIgnoreCase(li.tagName()))
                        s.writeText((n++) + ". " + li.text(), SIZE_BODY, false, LH_BODY);
                }
                s.vSpace(4f);
            }
            case "table" -> renderTable(s, el);
            default -> {
                for (Node child : el.childNodes()) renderNode(s, child);
            }
        }
    }

    private void renderTable(RenderState s, Element table) throws IOException {
        for (Element row : table.select("tr")) {
            List<Element> cells = row.select("th, td");
            if (cells.isEmpty()) continue;
            StringBuilder line = new StringBuilder();
            for (Element cell : cells) {
                String txt = cell.text();
                if (txt.length() > 22) txt = txt.substring(0, 19) + "...";
                line.append(String.format("%-23s", txt));
            }
            s.writeText(line.toString(), SIZE_BODY - 1, false, LH_BODY);
        }
        s.vSpace(4f);
    }

    // ─── Render state ─────────────────────────────────────────────────────

    private static final class RenderState {
        private final PDDocument        doc;
        private final PDType1Font       regular;
        private final PDType1Font       bold;
        private PDPageContentStream     cs;
        private float                   y;

        RenderState(PDDocument doc, PDType1Font regular, PDType1Font bold) throws IOException {
            this.doc     = doc;
            this.regular = regular;
            this.bold    = bold;
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y  = PAGE_HEIGHT - MARGIN;
        }

        void finish() throws IOException {
            if (cs != null) { cs.close(); cs = null; }
        }

        void vSpace(float space) throws IOException {
            y -= space;
            if (y < MARGIN) newPage();
        }

        void drawLine() throws IOException {
            cs.moveTo(MARGIN, y);
            cs.lineTo(PAGE_WIDTH - MARGIN, y);
            cs.stroke();
        }

        void writeText(String text, int fontSize, boolean useBold, float lineH) throws IOException {
            PDType1Font font = useBold ? bold : regular;
            for (String line : wrap(text, font, fontSize)) {
                if (y - lineH < MARGIN) newPage();
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(line);
                cs.endText();
                y -= lineH;
            }
        }

        private List<String> wrap(String text, PDType1Font font, int size) throws IOException {
            List<String> lines = new ArrayList<>();
            String[] words = text.split("\\s+");
            StringBuilder cur = new StringBuilder();
            for (String word : words) {
                String candidate = cur.isEmpty() ? word : cur + " " + word;
                float w = font.getStringWidth(sanitise(candidate)) / 1000f * size;
                if (w > CONTENT_W && !cur.isEmpty()) {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(candidate);
                }
            }
            if (!cur.isEmpty()) lines.add(cur.toString());
            return lines.isEmpty() ? List.of("") : lines;
        }

        /** PDFBox getStringWidth chokes on non-WinAnsi chars; strip them. */
        private static String sanitise(String s) {
            return s.replaceAll("[^\\x20-\\x7E\\xA0-\\xFF]", "?");
        }
    }
}
