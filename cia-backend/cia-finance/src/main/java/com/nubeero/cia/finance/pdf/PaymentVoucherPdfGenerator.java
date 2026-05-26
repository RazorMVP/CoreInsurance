package com.nubeero.cia.finance.pdf;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Generates a Thymeleaf-rendered payment voucher PDF from a posted
 * {@link Payment}. Never throws — catches every exception, logs WARN, and
 * returns {@code null}. The {@code PaymentService.post()} flow tolerates
 * null (leaves {@code pdf_path} unset) so PDF failures never roll back the
 * payment save.
 *
 * <p>Header label varies by {@link CreditNote#getEntityType()}:
 * CLAIM → "CLAIM SETTLEMENT VOUCHER", COMMISSION → "COMMISSION VOUCHER",
 * REINSURANCE → "FAC PREMIUM VOUCHER", ENDORSEMENT → "ENDORSEMENT REFUND
 * VOUCHER". Other types fall back to the generic "PAYMENT VOUCHER".
 *
 * <p>"Paid to" block resolves the beneficiary via
 * {@link BeneficiaryProfileResolverDispatcher} — name always non-null,
 * address may be null.
 *
 * @since Slice β — Task 13, F7 receipt + payment-voucher PDF generation
 */
@Component
public class PaymentVoucherPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(PaymentVoucherPdfGenerator.class);

    private final TemplateEngine                       templateEngine;
    private final HtmlToPdfConverter                   htmlToPdfConverter;
    private final BeneficiaryProfileResolverDispatcher resolverDispatcher;

    public PaymentVoucherPdfGenerator(TemplateEngine templateEngine,
                                        HtmlToPdfConverter htmlToPdfConverter,
                                        BeneficiaryProfileResolverDispatcher resolverDispatcher) {
        this.templateEngine     = templateEngine;
        this.htmlToPdfConverter = htmlToPdfConverter;
        this.resolverDispatcher = resolverDispatcher;
    }

    /**
     * Renders the payment-voucher template + converts to PDF bytes.
     *
     * @return PDF bytes, or {@code null} on any rendering / conversion failure
     */
    public byte[] generate(Payment payment) {
        try {
            CreditNote cn = payment.getCreditNote();
            BeneficiaryProfile profile = resolverDispatcher.resolve(cn);

            Context ctx = new Context();
            ctx.setVariable("headerLabel",             headerLabelFor(cn.getEntityType()));
            ctx.setVariable("paymentNumber",           payment.getPaymentNumber());
            ctx.setVariable("paymentDate",             payment.getPaymentDate().toString());
            ctx.setVariable("beneficiaryName",         profile.name());
            ctx.setVariable("beneficiaryAddressLine1", profile.addressLine1());
            ctx.setVariable("beneficiaryAddressLine2", profile.addressLine2());
            ctx.setVariable("amountFormatted",         formatNaira(payment.getAmount()));
            ctx.setVariable("paymentMethod",           payment.getPaymentMethod().name().replace('_', ' '));
            ctx.setVariable("creditNoteNumber",        cn.getCreditNoteNumber());
            ctx.setVariable("entityReference",         cn.getEntityReference());
            ctx.setVariable("narration",               payment.getNarration() == null ? "" : payment.getNarration());

            String html = templateEngine.process("pdf/payment-voucher", ctx);
            return htmlToPdfConverter.convert(html);
        } catch (Exception e) {
            log.warn("PaymentVoucherPdfGenerator failed for payment {}: {}",
                     payment.getId(), e.getMessage(), e);
            return null;
        }
    }

    private static String headerLabelFor(FinanceEntityType type) {
        if (type == null) return "PAYMENT VOUCHER";
        return switch (type) {
            case CLAIM         -> "CLAIM SETTLEMENT VOUCHER";
            case COMMISSION    -> "COMMISSION VOUCHER";
            case REINSURANCE   -> "FAC PREMIUM VOUCHER";
            case ENDORSEMENT   -> "ENDORSEMENT REFUND VOUCHER";
            default            -> "PAYMENT VOUCHER";
        };
    }

    private static String formatNaira(BigDecimal amount) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("en-NG"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "₦" + nf.format(amount);
    }
}
