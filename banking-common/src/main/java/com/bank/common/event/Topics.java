package com.bank.common.event;

/**
 * Kafka topic names. Each topic has exactly one producing service (its owner).
 */
public final class Topics {

    /** Owner: account-service. Facts about balances and money movement. */
    public static final String ACCOUNT_EVENTS = "account-events";

    /** Owner: customer-service. Customer profile and KYC changes. */
    public static final String CUSTOMER_EVENTS = "customer-events";

    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }
}
