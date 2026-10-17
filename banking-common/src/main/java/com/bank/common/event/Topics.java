package com.bank.common.event;

/**
 * Kafka topic names. Each topic has exactly one producing service (its owner).
 */
public final class Topics {

    /** Owner: account-service. Facts about balances and money movement. */
    public static final String ACCOUNT_EVENTS = "account-events";

    /** Owner: customer-service. Customer profile and KYC changes. */
    public static final String CUSTOMER_EVENTS = "customer-events";

    /** Owner: payment-service. Clearing-network outcomes for interbank transfers. */
    public static final String PAYMENT_EVENTS = "payment-events";

    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }
}
