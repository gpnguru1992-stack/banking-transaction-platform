package com.bank.notification.service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** Customer-facing message text. Account numbers are always masked to their last four characters. */
public final class MessageTemplates {

    private MessageTemplates() {
    }

    public record Message(String subject, String body) {
    }

    public static Message debit(String account, String toAccount, BigDecimal amount, String currency, BigDecimal balance) {
        return new Message("Debit alert: " + money(amount, currency),
                "%s has been debited from your account %s towards %s. Available balance: %s. If you did not make this transfer, call us immediately."
                        .formatted(money(amount, currency), mask(account), mask(toAccount), money(balance, currency)));
    }

    public static Message credit(String account, String fromAccount, BigDecimal amount, String currency, BigDecimal balance) {
        return new Message("Credit alert: " + money(amount, currency),
                "%s has been credited to your account %s from %s. Available balance: %s."
                        .formatted(money(amount, currency), mask(account), mask(fromAccount), money(balance, currency)));
    }

    public static Message deposit(String account, BigDecimal amount, String currency, BigDecimal balance) {
        return new Message("Deposit received: " + money(amount, currency),
                "%s has been deposited to your account %s. Available balance: %s."
                        .formatted(money(amount, currency), mask(account), money(balance, currency)));
    }

    public static Message transferFailed(String account, BigDecimal amount, String currency, String reason) {
        return new Message("Transfer not completed",
                "Your transfer of %s from account %s was not completed: %s. No money has left your account."
                        .formatted(money(amount, currency), mask(account), reason));
    }

    public static String mask(String account) {
        if (account == null || account.length() <= 4) {
            return "XXXX";
        }
        return "XX" + account.substring(account.length() - 4);
    }

    static String money(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        try {
            format.setCurrency(Currency.getInstance(currency));
        } catch (IllegalArgumentException ignored) {
            // keep default currency symbol
        }
        return format.format(amount);
    }
}
