package com.nubeero.cia.quotation;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.setup.quote.QuoteConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuotePdfService {

    private final QuoteService         quoteService;
    private final QuoteConfigService   quoteConfigService;
    private final HtmlToPdfConverter   htmlToPdfConverter;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Transactional(readOnly = true)
    public byte[] generatePdf(UUID quoteId) {
        Quote q = quoteService.findOrThrow(quoteId);

        if (q.getStatus() != QuoteStatus.APPROVED && q.getStatus() != QuoteStatus.CONVERTED) {
            throw new BusinessRuleException("QUOTE_NOT_APPROVED",
                    "PDF can only be generated for APPROVED or CONVERTED quotes");
        }

        int validityDays = quoteConfigService.fetchConfig().getValidityDays();
        String html = buildHtml(q, validityDays);

        try {
            return htmlToPdfConverter.convert(html);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate quote PDF for " + q.getQuoteNumber(), e);
        }
    }

    // ── HTML builder ──────────────────────────────────────────────────────────

    private String buildHtml(Quote q, int validityDays) {
        LocalDate issueDate   = q.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        LocalDate expiryDate  = issueDate.plusDays(validityDays);

        BigDecimal totalGross   = q.getTotalPremium();
        BigDecimal totalItemNet = q.getRisks().stream()
                .map(QuoteRisk::getPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quoteLoading = sumAdjustments(q.getQuoteLoadings(), totalGross);
        BigDecimal quoteDiscount= sumAdjustments(q.getQuoteDiscounts(), totalGross.add(quoteLoading));
        BigDecimal finalNet     = q.getNetPremium();

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html><html><head>
                <meta charset="UTF-8"/>
                <style>
                  body { font-family: Helvetica, Arial, sans-serif; font-size: 10pt; color: #1a1a1a; margin: 0; padding: 32px; }
                  h1 { font-size: 18pt; margin: 0 0 4px; }
                  h2 { font-size: 11pt; margin: 16px 0 6px; text-transform: uppercase; letter-spacing: 1px; color: #444; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
                  table { width: 100%%; border-collapse: collapse; margin-bottom: 10px; font-size: 9pt; }
                  th, td { border: 1px solid #ccc; padding: 4px 6px; text-align: left; }
                  th { background: #f0f0f0; font-weight: bold; }
                  .right { text-align: right; }
                  .amber { color: #b45309; }
                  .rose  { color: #be185d; }
                  .teal  { color: #0f766e; font-weight: bold; }
                  .net-box { border: 2px solid #0f766e; background: #f0fdf9; padding: 8px 12px; margin: 12px 0; display: flex; justify-content: space-between; }
                  .subjectivity ol { margin: 4px 0 0 16px; }
                  .subjectivity li { margin-bottom: 4px; }
                  .sig-row { display: flex; gap: 60px; margin-top: 24px; }
                  .sig-block { flex: 1; border-top: 1px solid #888; padding-top: 4px; }
                  .footer { font-size: 8pt; color: #888; text-align: center; margin-top: 24px; }
                  .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; margin-bottom: 12px; }
                  .info-label { font-size: 8pt; color: #666; text-transform: uppercase; letter-spacing: 0.5px; }
                  .info-value { font-weight: bold; }
                </style>
                </head><body>
                """);

        // ── Header ─────────────────────────────────────────────────────────────
        sb.append("<div style='display:flex;justify-content:space-between;margin-bottom:16px;'>");
        sb.append("<div><h1>INSURANCE QUOTATION</h1><p style='color:#666;margin:0;'>NubSure — Powered by Nubeero Technologies</p></div>");
        sb.append("<div style='text-align:right;'>");
        sb.append("<p style='font-family:Courier,monospace;font-size:13pt;font-weight:bold;margin:0;'>").append(q.getQuoteNumber()).append("</p>");
        sb.append("<p style='color:#666;margin:0;font-size:9pt;'>Issue Date: ").append(issueDate.format(DATE_FMT)).append("</p>");
        sb.append("</div></div><hr/>");

        // ── Info grid ──────────────────────────────────────────────────────────
        sb.append("<div class='info-grid'>");
        addInfo(sb, "Prepared For",    q.getCustomerName());
        addInfo(sb, "Policy Period",   fmt(q.getPolicyStartDate()) + " → " + fmt(q.getPolicyEndDate()));
        addInfo(sb, "Product",         q.getProductName());
        addInfo(sb, "Quote Validity",  "Valid for " + validityDays + " days (expires " + expiryDate.format(DATE_FMT) + ")");
        addInfo(sb, "Class of Business", q.getClassOfBusinessName());
        addInfo(sb, "Business Type",   q.getBusinessType().name().replace('_', ' '));
        sb.append("</div><hr/>");

        // ── Risk items ─────────────────────────────────────────────────────────
        sb.append("<h2>Risk Details &amp; Premium Breakdown</h2>");
        int itemNo = 1;
        for (QuoteRisk r : q.getRisks()) {
            if (r.getDeletedAt() != null) continue;
            sb.append("<p style='font-size:9pt;font-weight:bold;margin:8px 0 4px;'>")
              .append("Item ").append(itemNo++).append(" — ").append(r.getDescription())
              .append("</p>");

            sb.append("<table><tr><th>Description</th><th class='right'>Sum Insured</th>")
              .append("<th class='right'>Rate (%)</th><th class='right'>Gross Premium</th></tr>");
            sb.append("<tr><td>").append(r.getDescription()).append("</td>")
              .append("<td class='right'>").append(money(r.getSumInsured())).append("</td>")
              .append("<td class='right'>").append(r.getRate()).append("%</td>")
              .append("<td class='right'>").append(money(r.getGrossPremium())).append("</td></tr></table>");

            appendAdjustments(sb, "Loadings", r.getLoadings(), r.getGrossPremium(), "amber");
            appendAdjustments(sb, "Discounts", r.getDiscounts(),
                    r.getGrossPremium().add(sumAdjustments(r.getLoadings(), r.getGrossPremium())), "rose");

            sb.append("<p style='text-align:right;font-size:9pt;'>")
              .append("<strong>Item Net Premium: </strong>")
              .append("<span class='teal'>").append(money(r.getPremium())).append("</span></p>");
        }

        // ── Quote-level adjustments ────────────────────────────────────────────
        if (!q.getQuoteLoadings().isEmpty() || !q.getQuoteDiscounts().isEmpty()) {
            sb.append("<h2>Quote-Level Adjustments</h2>");
            sb.append("<table><tr><th>Description</th><th class='right'>Amount</th></tr>");
            sb.append("<tr><td>Sum of Item Gross Premiums (base for % calculations)</td>")
              .append("<td class='right'>").append(money(totalGross)).append("</td></tr>");
            sb.append("<tr><td>Sum of Item Net Premiums</td>")
              .append("<td class='right'>").append(money(totalItemNet)).append("</td></tr>");
            for (AdjustmentEntry l : q.getQuoteLoadings()) {
                BigDecimal amt = computeAmount(l, totalGross);
                sb.append("<tr><td class='amber'>+ Loading: ").append(l.getTypeName())
                  .append(" (").append(formatAdjFmt(l)).append(")</td>")
                  .append("<td class='right amber'>").append(money(amt)).append("</td></tr>");
            }
            for (AdjustmentEntry d : q.getQuoteDiscounts()) {
                BigDecimal base = totalGross.add(quoteLoading);
                BigDecimal amt  = computeAmount(d, base);
                sb.append("<tr><td class='rose'>− Discount: ").append(d.getTypeName())
                  .append(" (").append(formatAdjFmt(d)).append(")</td>")
                  .append("<td class='right rose'>").append(money(amt)).append("</td></tr>");
            }
            sb.append("</table>");
        }

        // ── Final net ──────────────────────────────────────────────────────────
        sb.append("<div class='net-box'>")
          .append("<span style='font-size:11pt;font-weight:bold;color:#0f766e;'>FINAL NET PREMIUM</span>")
          .append("<span style='font-size:14pt;font-weight:bold;color:#0f766e;'>").append(money(finalNet)).append("</span>")
          .append("</div>");

        // ── Clauses ────────────────────────────────────────────────────────────
        if (!q.getSelectedClauseIds().isEmpty()) {
            sb.append("<h2>Applicable Clauses</h2>");
            // Clause text is not stored on the quote — only IDs.
            // Production: join to clause_bank table. Here we list IDs as a guard.
            sb.append("<p style='font-size:9pt;color:#666;'>")
              .append(q.getSelectedClauseIds().size())
              .append(" clause(s) attached — see full policy document for clause text.</p>");
        }

        // ── General Subjectivity ───────────────────────────────────────────────
        sb.append("<h2>General Subjectivity</h2><div class='subjectivity'><ol>");
        sb.append("<li>This quote is subject to <strong>no known loss or reported loss</strong> till date.</li>");
        sb.append("<li>This quotation is valid for <strong>").append(validityDays)
          .append(" days</strong> from the date of issue (").append(issueDate.format(DATE_FMT))
          .append("). It will expire on <strong>").append(expiryDate.format(DATE_FMT))
          .append("</strong>.</li>");
        sb.append("<li>This quote is subject to a <strong>satisfactory survey report</strong>.</li>");
        sb.append("</ol></div>");

        // ── Signatures ─────────────────────────────────────────────────────────
        sb.append("<div class='sig-row'>");
        sb.append("<div class='sig-block'><p style='margin:0;font-weight:bold;'>")
          .append(nvl(q.getInputterName(), "—")).append("</p>")
          .append("<p style='margin:0;color:#666;font-size:9pt;'>Prepared by (Underwriter)</p></div>");
        sb.append("<div class='sig-block'><p style='margin:0;font-weight:bold;'>")
          .append(nvl(q.getApproverName(), "—")).append("</p>")
          .append("<p style='margin:0;color:#666;font-size:9pt;'>Approved by</p></div>");
        sb.append("</div>");

        sb.append("<p class='footer'>NubSure by Nubeero Technologies · Computer generated quotation.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addInfo(StringBuilder sb, String label, String value) {
        sb.append("<div><p class='info-label'>").append(label).append("</p>")
          .append("<p class='info-value'>").append(value).append("</p></div>");
    }

    private void appendAdjustments(StringBuilder sb, String title,
                                    List<AdjustmentEntry> entries, BigDecimal base, String colorClass) {
        if (entries == null || entries.isEmpty()) return;
        sb.append("<table><tr><th colspan='3'>").append(title).append("</th></tr>")
          .append("<tr><th>Type</th><th>Format</th><th class='right'>Amount</th></tr>");
        for (AdjustmentEntry e : entries) {
            BigDecimal amt = computeAmount(e, base);
            sb.append("<tr class='").append(colorClass).append("'>")
              .append("<td>").append(e.getTypeName()).append("</td>")
              .append("<td>").append(formatAdjFmt(e)).append("</td>")
              .append("<td class='right'>").append(money(amt)).append("</td></tr>");
        }
        sb.append("</table>");
    }

    private BigDecimal computeAmount(AdjustmentEntry e, BigDecimal base) {
        if (e.getFormat() == AdjustmentFormat.PERCENT) {
            return base.multiply(e.getValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return e.getValue().setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumAdjustments(List<AdjustmentEntry> entries, BigDecimal base) {
        if (entries == null || entries.isEmpty()) return BigDecimal.ZERO;
        return entries.stream()
                .map(e -> computeAmount(e, base))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String formatAdjFmt(AdjustmentEntry e) {
        return e.getFormat() == AdjustmentFormat.PERCENT ? e.getValue() + "%" : "Flat";
    }

    private String money(BigDecimal v) {
        if (v == null) return "₦0.00";
        return "₦" + v.setScale(2, RoundingMode.HALF_UP)
                .toPlainString().replaceAll("(\\d)(?=(\\d{3})+\\.)", "$1,");
    }

    private String fmt(LocalDate d) {
        return d == null ? "—" : d.format(DATE_FMT);
    }

    private String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
