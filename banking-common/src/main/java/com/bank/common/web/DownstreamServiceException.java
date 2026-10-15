package com.bank.common.web;

import org.springframework.http.HttpStatus;

/** A dependency is unavailable (circuit open, timeout). The request may be retried later. */
public class DownstreamServiceException extends BusinessException {

    public DownstreamServiceException(String service, Throwable cause) {
        super("DEPENDENCY_UNAVAILABLE", service + " is temporarily unavailable, please retry", HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
