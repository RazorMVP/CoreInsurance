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

    // ── HTML builder (uses only tags HtmlToPdfConverter supports: h1/h2/p/table/ol/hr) ──

    private String buildHtml(Quote q, int validityDays) {
        LocalDate issueDate  = q.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        LocalDate expiryDate = issueDate.plusDays(validityDays);

        BigDecimal totalGross    = q.getTotalPremium();
        BigDecimal totalItemNet  = q.getRisks().stream()
                .map(QuoteRisk::getPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quoteLoading  = sumAdjustments(q.getQuoteLoadings(), totalGross);
        BigDecimal quoteDiscount = sumAdjustments(q.getQuoteDiscounts(), totalGross.add(quoteLoading));
        BigDecimal finalNet      = q.getNetPremium();

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");

        // Header
        sb.append("<h1>INSURANCE QUOTATION</h1>");
        sb.append("<p>NubSure - Powered by Nubeero Technologies</p>");
        sb.append("<p>Quote Number: ").append(q.getQuoteNumber())
          .append(" | Issue Date: ").append(issueDate.format(DATE_FMT)).append("</p>");
        sb.append("<hr/>");

        // Info table
        sb.append("<table><thead><tr><th>Field</th><th>Detail</th></tr></thead><tbody>");
        sb.append("<tr><td>Prepared For</td><td>").append(q.getCustomerName()).append("</td></tr>");
        sb.append("<tr><td>Product</td><td>").append(q.getProductName()).append("</td></tr>");
        sb.append("<tr><td>Class of Business</td><td>").append(q.getClassOfBusinessName()).append("</td></tr>");
        sb.append("<tr><td>Business Type</td><td>").append(q.getBusinessType().name().replace('_', ' ')).append("</td></tr>");
        sb.append("<tr><td>Policy Period</td><td>").append(fmt(q.getPolicyStartDate()))
          .append(" to ").append(fmt(q.getPolicyEndDate())).append("</td></tr>");
        sb.append("<tr><td>Quote Validity</td><td>Valid for ").append(validityDays)
          .append(" days. Expires ").append(expiryDate.format(DATE_FMT)).append("</td></tr>");
        sb.append("</tbody></table><hr/>");

        // Risk items
        sb.append("<h2>Risk Details and Premium Breakdown</h2>");
        int itemNo = 1;
        for (QuoteRisk r : q.getRisks()) {
            if (r.getDeletedAt() != null) continue;
            sb.append("<p><strong>Item ").append(itemNo++).append(" - ").append(r.getDescription()).append("</strong></p>");
            sb.append("<table><tr><th>Description</th><th>Sum Insured</th><th>Rate (%)</th><th>Gross Premium</th></tr>");
            sb.append("<tr><td>").append(r.getDescription()).append("</td>")
              .append("<td>").append(money(r.getSumInsured())).append("</td>")
              .append("<td>").append(r.getRate()).append("%</td>")
              .append("<td>").append(money(r.getGrossPremium())).append("</td></tr></table>");
            appendAdjTable(sb, "Loadings",  r.getLoadings(),  r.getGrossPremium());
            appendAdjTable(sb, "Discounts", r.getDiscounts(),
                    r.getGrossPremium().add(sumAdjustments(r.getLoadings(), r.getGrossPremium())));
            sb.append("<p><strong>Item Net Premium: ").append(money(r.getPremium())).append("</strong></p>");
        }

        // Quote-level adjustments
        if (!q.getQuoteLoadings().isEmpty() || !q.getQuoteDiscounts().isEmpty()) {
            sb.append("<h2>Quote-Level Adjustments</h2>");
            sb.append("<table><tr><th>Description</th><th>Amount</th></tr>");
            sb.append("<tr><td>Sum of Item Gross Premiums (% base)</td><td>").append(money(totalGross)).append("</td></tr>");
            sb.append("<tr><td>Sum of Item Net Premiums</td><td>").append(money(totalItemNet)).append("</td></tr>");
            for (AdjustmentEntry l : q.getQuoteLoadings()) {
                BigDecimal amt = computeAmount(l, totalGross);
                sb.append("<tr><td>+ Loading: ").append(l.getTypeName())
                  .append(" (").append(formatAdjFmt(l)).append(")</td>")
                  .append("<td>").append(money(amt)).append("</td></tr>");
            }
            for (AdjustmentEntry d : q.getQuoteDiscounts()) {
                BigDecimal base = totalGross.add(quoteLoading);
                BigDecimal amt  = computeAmount(d, base);
                sb.append("<tr><td>- Discount: ").append(d.getTypeName())
                  .append(" (").append(formatAdjFmt(d)).append(")</td>")
                  .append("<td>").append(money(amt)).append("</td></tr>");
            }
            sb.append("</table>");
        }

        // Final net
        sb.append("<hr/>");
        sb.append("<p><strong>FINAL NET PREMIUM: ").append(money(finalNet)).append("</strong></p>");
        sb.append("<hr/>");

        // Clauses
        if (!q.getSelectedClauseIds().isEmpty()) {
            sb.append("<h2>Applicable Clauses</h2>");
            sb.append("<p>").append(q.getSelectedClauseIds().size())
              .append(" clause(s) attached. See full policy document for clause text.</p>");
        }

        // General Subjectivity
        sb.append("<h2>General Subjectivity</h2>");
        sb.append("<ol>");
        sb.append("<li>This quote is subject to no known loss or reported loss till date.</li>");
        sb.append("<li>This quotation is valid for ").append(validityDays)
          .append(" days from the date of issue (").append(issueDate.format(DATE_FMT))
          .append("). It will expire on ").append(expiryDate.format(DATE_FMT)).append(".</li>");
        sb.append("<li>This quote is subject to a satisfactory survey report.</li>");
        sb.append("</ol>");
        sb.append("<hr/>");

        // Signatures
        sb.append("<table><tr><th>Prepared by (Underwriter)</th><th>Approved by</th></tr>");
        sb.append("<tr><td>").append(nvl(q.getInputterName(), "-")).append("</td>")
          .append("<td>").append(nvl(q.getApproverName(), "-")).append("</td></tr></table>");

        sb.append("<p>NubSure by Nubeero Technologies. Computer generated quotation.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendAdjTable(StringBuilder sb, String title,
                                 List<AdjustmentEntry> entries, BigDecimal base) {
        if (entries == null || entries.isEmpty()) return;
        sb.append("<table><tr><th colspan='3'>").append(title).append("</th></tr>")
          .append("<tr><th>Type</th><th>Format</th><th>Amount</th></tr>");
        for (AdjustmentEntry e : entries) {
            BigDecimal amt = computeAmount(e, base);
            sb.append("<tr><td>").append(e.getTypeName()).append("</td>")
              .append("<td>").append(formatAdjFmt(e)).append("</td>")
              .append("<td>").append(money(amt)).append("</td></tr>");
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
        if (v == null) return "NGN 0.00";
        return "NGN " + v.setScale(2, RoundingMode.HALF_UP)
                .toPlainString().replaceAll("(\\d)(?=(\\d{3})+\\.)", "$1,");
    }

    private String fmt(LocalDate d) {
        return d == null ? "-" : d.format(DATE_FMT);
    }

    private String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
