package com.bank.payment.clearing;

public enum ClearingStatus {
    /** Waiting to be sent (immediately for IMPS/RTGS, at the next batch for NEFT). */
    QUEUED,
    /** Sent, but no answer (timeout / network error). Must be resolved by status inquiry, never by guessing. */
    UNKNOWN,
    SETTLED,
    REJECTED,
    /** Still unknown after all attempts; operations must reconcile with the network. */
    MANUAL_REVIEW
}
