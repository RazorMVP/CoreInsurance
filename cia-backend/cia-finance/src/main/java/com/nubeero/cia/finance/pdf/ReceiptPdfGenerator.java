package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Generates a Thymeleaf-rendered "OFFICIAL RECEIPT" PDF from a posted
 * {@link Receipt}. Never throws — catches every exception, logs WARN, and
 * returns {@code null}. The {@code ReceiptService.post()} flow tolerates
 * null (leaves {@code pdf_path} unset) so PDF failures never roll back the
 * receipt save.
 *
 * <p>The receipt's {@link com.nubeero.cia.finance.DebitNote} must be non-null
 * and eagerly loaded — the template reads customer name + policy reference
 * from it.
 *
 * @since Slice β — Task 10, F7 receipt + payment-voucher PDF generation
 */
@Component
public class ReceiptPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPdfGenerator.class);

    private final TemplateEngine     templateEngine;
    private final HtmlToPdfConverter htmlToPdfConverter;

    public ReceiptPdfGenerator(TemplateEngine templateEngine,
                                HtmlToPdfConverter htmlToPdfConverter) {
        this.templateEngine     = templateEngine;
        this.htmlToPdfConverter = htmlToPdfConverter;
    }

    /**
     * Renders the receipt template + converts to PDF bytes.
     *
     * @return PDF bytes, or {@code null} on any rendering / conversion failure
     */
    public byte[] generate(Receipt receipt) {
        try {
            Context ctx = new Context();
            ctx.setVariable("receiptNumber",   receipt.getReceiptNumber());
            ctx.setVariable("paymentDate",     receipt.getPaymentDate().toString());
            ctx.setVariable("customerName",    receipt.getDebitNote().getCustomerName());
            ctx.setVariable("amountFormatted", formatNaira(receipt.getAmount()));
            ctx.setVariable("paymentMethod",   receipt.getPaymentMethod().name().replace('_', ' '));
            ctx.setVariable("debitNoteNumber", receipt.getDebitNote().getDebitNoteNumber());
            ctx.setVariable("policyNumber",
                receipt.getDebitNote().getEntityType() == FinanceEntityType.POLICY
                    ? receipt.getDebitNote().getEntityReference()
                    : null);
            ctx.setVariable("narration",       receipt.getNarration() == null ? "" : receipt.getNarration());
            ctx.setVariable("postedBy",        receipt.getPostedBy() == null ? "system" : receipt.getPostedBy());

            String html = templateEngine.process("pdf/receipt", ctx);
            return htmlToPdfConverter.convert(html);
        } catch (Exception e) {
            log.warn("ReceiptPdfGenerator failed for receipt {}: {}",
                     receipt.getId(), e.getMessage(), e);
            return null;
        }
    }

    private static String formatNaira(BigDecimal amount) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("en-NG"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₦" + nf.format(amount);
    }
}
