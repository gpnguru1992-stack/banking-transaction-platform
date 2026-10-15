package com.bank.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @Test
    void retriesUntilMaxAttemptsThenFails() {
        Notification n = new Notification(UUID.randomUUID(), "CUST1001", Channel.EMAIL, "s", "b", "corr-12345678", NOW);
        n.markAttemptFailed("smtp down", 3, NOW.plusSeconds(10));
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        n.markAttemptFailed("smtp down", 3, NOW.plusSeconds(20));
        n.markAttemptFailed("smtp down", 3, NOW.plusSeconds(40));
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getAttempts()).isEqualTo(3);
    }

    @Test
    void olderContactEventDoesNotOverwriteNewerOne() {
        CustomerContact contact = new CustomerContact("CUST1001");
        assertThat(contact.apply("Asha", "new@example.com", "+911", true, true, NOW)).isTrue();
        assertThat(contact.apply("Asha", "old@example.com", "+911", true, true, NOW.minusSeconds(60))).isFalse();
        assertThat(contact.getEmail()).isEqualTo("new@example.com");
    }
}
