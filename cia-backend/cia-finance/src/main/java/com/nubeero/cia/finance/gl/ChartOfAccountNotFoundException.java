package com.nubeero.cia.finance.gl;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ChartOfAccountNotFoundException extends RuntimeException {

    public ChartOfAccountNotFoundException(String code) {
        super("Chart of account not found for code: " + code);
    }
}
