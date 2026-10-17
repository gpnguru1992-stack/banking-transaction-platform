package com.bank.common.event;

/** Indian interbank payment systems. */
public enum PaymentRail {
    /** Immediate Payment Service: instant, 24x7, up to the IMPS per-transaction cap. */
    IMPS,
    /** National Electronic Funds Transfer: settled in half-hourly batches, no amount limit. */
    NEFT,
    /** Real Time Gross Settlement: instant, large-value only (minimum amount applies). */
    RTGS
}
