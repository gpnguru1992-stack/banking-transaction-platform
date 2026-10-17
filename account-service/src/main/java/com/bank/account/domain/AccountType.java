package com.bank.account.domain;

public enum AccountType {
    SAVINGS,
    CURRENT,
    /** Bank-owned ledger account (suspense / clearing); never addressable through the public API. */
    INTERNAL
}
