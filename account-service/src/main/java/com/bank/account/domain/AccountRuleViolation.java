package com.bank.account.domain;

/** A domain rule broke (e.g. insufficient funds). Carries a stable code for clients and audit. */
public class AccountRuleViolation extends RuntimeException {

    private final String code;

    public AccountRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
