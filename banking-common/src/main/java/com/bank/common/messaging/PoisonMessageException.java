package com.bank.common.messaging;

/** A message that can never be processed; it is sent straight to the dead-letter topic. */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PoisonMessageException(String message) {
        super(message);
    }
}
