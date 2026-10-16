package com.bank.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    private Account accountWith(String balance) {
        Account account = new Account("ACC9001", "CUST9001", AccountType.SAVINGS, "INR", NOW);
        if (new BigDecimal(balance).signum() > 0) {
            account.credit(new BigDecimal(balance), NOW);
        }
        return account;
    }

    @Test
    void debitAndCreditAdjustBalance() {
        Account account = accountWith("1000.00");
        account.debit(new BigDecimal("250.50"), NOW);
        assertThat(account.getBalance()).isEqualByComparingTo("749.50");
        account.credit(new BigDecimal("0.50"), NOW);
        assertThat(account.getBalance()).isEqualByComparingTo("750.00");
    }

    @Test
    void cannotOverdraw() {
        Account account = accountWith("100.00");
        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01"), NOW))
                .isInstanceOf(AccountRuleViolation.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        Account account = accountWith("100.00");
        assertThatThrownBy(() -> account.debit(BigDecimal.ZERO, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.credit(new BigDecimal("-1"), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frozenAccountCannotMoveMoney() {
        Account account = accountWith("100.00");
        account.changeStatus(AccountStatus.FROZEN, NOW);
        assertThatThrownBy(() -> account.debit(BigDecimal.ONE, NOW)).isInstanceOf(AccountRuleViolation.class);
        assertThatThrownBy(() -> account.credit(BigDecimal.ONE, NOW)).isInstanceOf(AccountRuleViolation.class);
    }

    @Test
    void cannotCloseAccountWithMoneyInIt() {
        Account account = accountWith("1.00");
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.CLOSED, NOW)).isInstanceOf(AccountRuleViolation.class);
    }
}
