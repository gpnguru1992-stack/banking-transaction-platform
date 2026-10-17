package com.bank.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Interbank (IMPS/NEFT/RTGS) rules. {@code ownBankCode} is the first four letters of this bank's IFSC. */
@ConfigurationProperties(prefix = "banking.interbank")
public record InterbankProperties(
        BigDecimal maxAmount,
        BigDecimal impsMaxAmount,
        BigDecimal rtgsMinAmount,
        String ownBankCode) {

    public InterbankProperties {
        maxAmount = maxAmount == null ? new BigDecimal("1000000.00") : maxAmount;
        impsMaxAmount = impsMaxAmount == null ? new BigDecimal("500000.00") : impsMaxAmount;
        rtgsMinAmount = rtgsMinAmount == null ? new BigDecimal("200000.00") : rtgsMinAmount;
        ownBankCode = ownBankCode == null ? "BANK" : ownBankCode;
    }
}
