package com.nubeero.cia.tenant;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

public class TenantProvisioningException extends CiaException {

    public TenantProvisioningException(String errorCode, String message, HttpStatus httpStatus) {
        super(errorCode, message, httpStatus);
    }

    public TenantProvisioningException(String errorCode, String message, HttpStatus httpStatus, Throwable cause) {
        super(errorCode, message, httpStatus, cause);
    }
}
