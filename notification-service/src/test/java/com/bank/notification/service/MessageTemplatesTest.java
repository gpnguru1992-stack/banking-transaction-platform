package com.bank.notification.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTemplatesTest {

    @Test
    void accountNumbersAreMasked() {
        assertThat(MessageTemplates.mask("ACC1001")).isEqualTo("XX1001");
        assertThat(MessageTemplates.mask("123")).isEqualTo("XXXX");
        assertThat(MessageTemplates.mask(null)).isEqualTo("XXXX");
    }

    @Test
    void debitMessageNeverContainsFullAccountNumbers() {
        var message = MessageTemplates.debit("ACC1001", "ACC2001", new BigDecimal("10000"), "INR", new BigDecimal("90000"));
        assertThat(message.body()).contains("XX1001", "XX2001", "10,000").doesNotContain("ACC1001", "ACC2001");
        assertThat(message.subject()).startsWith("Debit alert");
    }

    @Test
    void failureMessageReassuresCustomer() {
        var message = MessageTemplates.transferFailed("ACC1001", new BigDecimal("5"), "INR", "Insufficient funds");
        assertThat(message.body()).contains("Insufficient funds", "No money has left your account");
    }
}
