package com.nubeero.cia.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the post-Slice-β contract of {@link HtmlToPdfConverter}:
 *
 * <ul>
 *   <li>The ₦ (U+20A6) glyph renders rather than getting sanitised to '?'.</li>
 *   <li>All existing HTML tags supported pre-refactor still render (smoke).</li>
 *   <li>Non-WinAnsi Latin glyphs (e.g. Ł U+0141) survive end-to-end.</li>
 * </ul>
 *
 * @since Slice β — Task 3, F7 receipt + payment-voucher PDF generation
 */
class HtmlToPdfConverterFontIT {

    private final HtmlToPdfConverter converter = new HtmlToPdfConverter();

    @Test
    @DisplayName("₦ glyph (U+20A6) renders correctly in generated PDF")
    void nairaGlyphRendersInPdf() throws IOException {
        String html = "<p>Amount: ₦250,000.00</p>";

        byte[] pdfBytes = converter.convert(html);
        assertThat(pdfBytes).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .as("Extracted PDF text must contain the ₦ glyph rather than '?' fallback")
                .contains("₦250,000.00");
        }
    }

    @Test
    @DisplayName("Existing tags (h1, p, table, ul) still render after font refactor")
    void existingTagsStillRender() throws IOException {
        String html = """
            <h1>Test Document</h1>
            <p>First paragraph.</p>
            <h2>Second Heading</h2>
            <ul>
              <li>Item one</li>
              <li>Item two</li>
            </ul>
            <table>
              <tr><th>Col A</th><th>Col B</th></tr>
              <tr><td>Cell 1</td><td>Cell 2</td></tr>
            </table>
            """;

        byte[] pdfBytes = converter.convert(html);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Test Document", "First paragraph",
                                       "Second Heading", "Item one", "Item two",
                                       "Cell 1", "Cell 2");
        }
    }

    @Test
    @DisplayName("Non-WinAnsi Latin glyphs (e.g. Ł U+0141) survive end-to-end — sanitise() removed")
    void nonWinAnsiGlyphSurvives() throws IOException {
        // Ł (U+0141, Latin Extended-A) is outside WinAnsi (0x20-0x7E + 0xA0-0xFF),
        // so the pre-refactor sanitise() would have replaced it with '?'.
        // NotoSans Latin covers Latin Extended-A, so PDType0Font renders it correctly.
        String html = "<p>Polish: Łódź</p>";

        byte[] pdfBytes = converter.convert(html);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .as("Pre-refactor sanitise() would have written '?' for Ł")
                .contains("Łódź");
        }
    }
}
