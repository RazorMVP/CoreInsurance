package com.nubeero.cia.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends CiaException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super("RESOURCE_NOT_FOUND",
              resourceType + " not found with id: " + id,
              HttpStatus.NOT_FOUND);
    }
}
