package com.nubeero.cia.common.exception;

import org.springframework.http.HttpStatus;

/** Server-side file-upload validation failure (bad type, too large, signature mismatch, scan reject). */
public class FileValidationException extends CiaException {

    public FileValidationException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
