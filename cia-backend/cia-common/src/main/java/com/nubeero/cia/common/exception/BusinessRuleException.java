package com.nubeero.cia.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends CiaException {

    public BusinessRuleException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
