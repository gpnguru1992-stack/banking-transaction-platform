package com.bank.payment.domain;

import java.math.BigDecimal;

public record SettlementLine(String currency, PaymentStatus status, long count, BigDecimal totalAmount) {
}
