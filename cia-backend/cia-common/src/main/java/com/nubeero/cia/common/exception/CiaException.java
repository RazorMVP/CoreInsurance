package com.nubeero.cia.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class CiaException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public CiaException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public CiaException(String errorCode, String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
