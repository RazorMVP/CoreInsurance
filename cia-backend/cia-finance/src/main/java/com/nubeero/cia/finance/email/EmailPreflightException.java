package com.nubeero.cia.finance.email;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link com.nubeero.cia.finance.ReceiptService#requestEmail(java.util.UUID)}
 * and the payment mirror when the email preflight check fails:
 *
 * <ul>
 *   <li>{@code RECEIPT_PDF_UNAVAILABLE} / {@code PAYMENT_PDF_UNAVAILABLE} —
 *       the slice-β PDF generation failed and {@code pdfPath} is null.</li>
 *   <li>{@code RECEIPT_RECIPIENT_UNRESOLVED} / {@code PAYMENT_RECIPIENT_UNRESOLVED} —
 *       no email address on file for the resolved beneficiary.</li>
 * </ul>
 *
 * <p>Inherits {@link CiaException}'s {@code errorCode + httpStatus} fields;
 * {@link com.nubeero.cia.common.exception.GlobalExceptionHandler} already
 * maps the base type to {@code ApiResponse.error(errorCode, message)} at
 * the carried status code — no separate handler registration required.
 * Status is always {@link HttpStatus#UNPROCESSABLE_ENTITY} (422); the
 * frontend toast routes by {@code errorCode}.
 *
 * @since Slice γ — F7 email transmission
 */
public class EmailPreflightException extends CiaException {

    public EmailPreflightException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
