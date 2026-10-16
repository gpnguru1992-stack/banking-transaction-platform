package com.bank.common.web;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, String id) {
        super(resource.toUpperCase().replace(' ', '_') + "_NOT_FOUND", resource + " not found: " + id, HttpStatus.NOT_FOUND);
    }
}
