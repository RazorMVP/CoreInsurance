package com.nubeero.cia.finance.email;

import com.nubeero.cia.common.email.EmailTemplateType;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Renders an email subject + HTML body from a JAR-default template for the
 * given {@link EmailTemplateType}. Slice δ extends this composer to check
 * tenant {@code email_template} overrides before falling back.
 *
 * <p>Subjects are hardcoded per type in γ — moved to template metadata
 * (or a sibling {@code -subject.txt} file) in δ if tenant override is
 * required.
 *
 * @since Slice γ — F7 email transmission
 */
@Service
public class EmailBodyComposer {

    private final TemplateEngine templateEngine;

    public EmailBodyComposer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public EmailContent compose(EmailTemplateType type, Map<String, Object> mergeFields) {
        String subject = subjectFor(type, mergeFields);
        String bodyHtml = renderBody(type, mergeFields);
        return new EmailContent(subject, bodyHtml);
    }

    private static String subjectFor(EmailTemplateType type, Map<String, Object> fields) {
        return switch (type) {
            case RECEIPT_EMAIL -> "Receipt " + fields.getOrDefault("receiptNumber", "") + " — payment received";
            case PAYMENT_VOUCHER_EMAIL -> "Payment voucher " + fields.getOrDefault("paymentNumber", "");
        };
    }

    private String renderBody(EmailTemplateType type, Map<String, Object> fields) {
        Context ctx = new Context();
        ctx.setVariables(fields);
        String templatePath = switch (type) {
            case RECEIPT_EMAIL -> "email/receipt-default";
            case PAYMENT_VOUCHER_EMAIL -> "email/payment-voucher-default";
        };
        return templateEngine.process(templatePath, ctx);
    }
}
