package com.bank.account.domain;

public enum InterbankStatus {
    /** Debited from the customer; waiting for the clearing network. */
    PENDING,
    /** Credited at the beneficiary bank (UTR known). */
    SETTLED,
    /** Rejected by the network or beneficiary bank; money returned to the customer. */
    REFUNDED,
    /** Rejected by our own checks; no money moved. */
    FAILED
}
