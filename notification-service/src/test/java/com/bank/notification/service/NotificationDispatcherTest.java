package com.bank.notification.service;

import com.bank.notification.channel.NotificationSender;
import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.CustomerContactRepository;
import com.bank.notification.domain.Notification;
import com.bank.notification.domain.NotificationRepository;
import com.bank.notification.domain.NotificationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final CustomerContactRepository contacts = mock(CustomerContactRepository.class);
    private final NotificationSender email = mock(NotificationSender.class);
    private NotificationDispatcher dispatcher;
    private CustomerContact asha;

    @BeforeEach
    void setUp() {
        when(email.channel()).thenReturn(Channel.EMAIL);
        dispatcher = new NotificationDispatcher(notifications, contacts, List.of(email),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), 5, Duration.ofSeconds(10));
        asha = new CustomerContact("CUST1001");
        asha.apply("Asha", "asha@example.com", null, true, false, NOW);
    }

    private Notification pending(Channel channel) {
        return new Notification(UUID.randomUUID(), "CUST1001", channel, "subject", "body", null, NOW);
    }

    @Test
    void sendsToOptedInRecipient() {
        when(contacts.findById("CUST1001")).thenReturn(Optional.of(asha));
        when(email.recipient(asha)).thenReturn(Optional.of("asha@example.com"));
        Notification n = pending(Channel.EMAIL);

        dispatcher.dispatch(n);

        verify(email).send("asha@example.com", n);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void providerFailureIsRetriedWithBackoff() {
        when(contacts.findById("CUST1001")).thenReturn(Optional.of(asha));
        when(email.recipient(asha)).thenReturn(Optional.of("asha@example.com"));
        doThrow(new IllegalStateException("SMTP 421")).when(email).send(anyString(), any());
        Notification n = pending(Channel.EMAIL);

        dispatcher.dispatch(n);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isAfter(NOW);
        assertThat(n.getLastError()).contains("SMTP 421");
    }

    @Test
    void optedOutCustomerIsSkipped() {
        when(contacts.findById("CUST1001")).thenReturn(Optional.of(asha));
        when(email.recipient(asha)).thenReturn(Optional.empty());
        Notification n = pending(Channel.EMAIL);

        dispatcher.dispatch(n);

        verify(email, never()).send(anyString(), any());
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
    }

    @Test
    void unknownContactIsRetriedLater() {
        when(contacts.findById("CUST1001")).thenReturn(Optional.empty());
        Notification n = pending(Channel.EMAIL);

        dispatcher.dispatch(n);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getAttempts()).isEqualTo(1);
    }
}
