package com.nubeero.cia.documents;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
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
            PDFont regular = loadFont(doc, "/fonts/NotoSans-Regular.ttf");
            PDFont bold    = loadFont(doc, "/fonts/NotoSans-Bold.ttf");
            RenderState state = new RenderState(doc, regular, bold);

            for (Node child : jsoupDoc.body().childNodes()) {
                renderNode(state, child);
            }
            state.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDFont loadFont(PDDocument doc, String resourcePath) throws IOException {
        try (InputStream in = HtmlToPdfConverter.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Font resource not found on classpath: " + resourcePath);
            }
            return PDType0Font.load(doc, in);
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

    // ─── Glyph guard ──────────────────────────────────────────────────────

    /**
     * Replace any code point the given font cannot encode with a safe fallback
     * ('?'), so {@code wrap()}'s {@code getStringWidth} and {@code showText}
     * never throw "No glyph for U+XXXX". Logs a single deduped WARN listing the
     * substituted code points when it has to sanitise. Returns the input
     * unchanged (same reference) when every glyph is encodable — the
     * overwhelmingly common path — so there is zero allocation for normal text.
     *
     * <p>Detection primitive: {@link PDFont#getStringWidth(String)} encodes the
     * string to measure it and throws {@link IllegalArgumentException} for an
     * unmappable code point (it also declares {@link IOException}). PDType0Font's
     * {@code encode(String)} is {@code protected} in PDFBox 3.x, so width-probing
     * is the accessible equivalent and detects the same condition with no side
     * effect on the document.
     */
    static String sanitizeToFont(String text, PDFont font) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = null;            // lazily allocated only on first miss
        Set<Integer> substituted = null;    // deduped code points for the WARN
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            boolean encodable;
            try {
                font.getStringWidth(new String(Character.toChars(cp)));
                encodable = true;
            } catch (IllegalArgumentException | IOException e) {
                encodable = false;
            }
            if (!encodable) {
                if (sb == null) {
                    sb = new StringBuilder(text.length()).append(text, 0, i);
                    substituted = new LinkedHashSet<>();
                }
                sb.append('?');
                substituted.add(cp);
            } else if (sb != null) {
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }
        if (sb == null) return text;        // all encodable — no change, no alloc
        log.warn("PDF render: {} unsupported glyph(s) replaced with '?': {}",
                substituted.size(),
                substituted.stream()
                        .map(c -> String.format("U+%04X", c))
                        .collect(Collectors.joining(", ")));
        return sb.toString();
    }

    // ─── Render state ─────────────────────────────────────────────────────

    private static final class RenderState {
        private final PDDocument        doc;
        private final PDFont            regular;
        private final PDFont            bold;
        private PDPageContentStream     cs;
        private float                   y;

        RenderState(PDDocument doc, PDFont regular, PDFont bold) throws IOException {
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
            PDFont font = useBold ? bold : regular;
            // Sanitise once against the font this segment renders with, BEFORE wrap()
            // (font.getStringWidth) and showText() — both throw "No glyph for U+XXXX"
            // on an unencodable code point. This is the single chokepoint: wrap()
            // receives the sanitised text, so every line handed to showText() is
            // already safe.
            text = sanitizeToFont(text, font);
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

        private List<String> wrap(String text, PDFont font, int size) throws IOException {
            List<String> lines = new ArrayList<>();
            String[] words = text.split("\\s+");
            StringBuilder cur = new StringBuilder();
            for (String word : words) {
                String candidate = cur.isEmpty() ? word : cur + " " + word;
                float w = font.getStringWidth(candidate) / 1000f * size;
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
    }
}
