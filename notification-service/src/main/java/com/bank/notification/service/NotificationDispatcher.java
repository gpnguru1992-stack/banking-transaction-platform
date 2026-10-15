package com.bank.notification.service;

import com.bank.common.observability.CorrelationId;
import com.bank.notification.channel.NotificationSender;
import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.CustomerContactRepository;
import com.bank.notification.domain.Notification;
import com.bank.notification.domain.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers pending notifications with exponential backoff. If the customer's contact details have not
 * arrived yet (customer-events and account-events are separate topics), the attempt is retried later.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository notifications;
    private final CustomerContactRepository contacts;
    private final Map<Channel, NotificationSender> senders = new EnumMap<>(Channel.class);
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final Duration baseBackoff;

    public NotificationDispatcher(NotificationRepository notifications, CustomerContactRepository contacts,
                                  List<NotificationSender> senders, Clock clock, MeterRegistry meterRegistry,
                                  @Value("${banking.notification.max-attempts:5}") int maxAttempts,
                                  @Value("${banking.notification.base-backoff:PT10S}") Duration baseBackoff) {
        this.notifications = notifications;
        this.contacts = contacts;
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
    }

    @Scheduled(fixedDelayString = "${banking.notification.poll-interval:2000}")
    @Transactional
    public void dispatchDue() {
        for (Notification n : notifications.lockDue(clock.instant(), 100)) {
            CorrelationId.runWith(n.getCorrelationId(), () -> dispatch(n));
        }
    }

    void dispatch(Notification n) {
        NotificationSender sender = senders.get(n.getChannel());
        if (sender == null) {
            n.markSkipped("No provider configured for " + n.getChannel());
            record(n);
            return;
        }
        Optional<CustomerContact> contact = contacts.findById(n.getCustomerId());
        if (contact.isEmpty()) {
            n.markAttemptFailed("Customer contact not yet known", maxAttempts, nextAttempt(n));
            record(n);
            return;
        }
        Optional<String> recipient = sender.recipient(contact.get());
        if (recipient.isEmpty()) {
            n.markSkipped("Customer not reachable or opted out on " + n.getChannel());
            record(n);
            return;
        }
        try {
            sender.send(recipient.get(), n);
            n.markSent(recipient.get(), clock.instant());
        } catch (RuntimeException e) {
            log.warn("Delivery of notification {} via {} failed (attempt {}): {}", n.getId(), n.getChannel(),
                    n.getAttempts() + 1, e.getMessage());
            n.markAttemptFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), maxAttempts, nextAttempt(n));
        }
        record(n);
    }

    private Instant nextAttempt(Notification n) {
        long factor = 1L << Math.min(n.getAttempts(), 10);
        return clock.instant().plus(baseBackoff.multipliedBy(factor));
    }

    private void record(Notification n) {
        meterRegistry.counter("banking.notifications", "channel", n.getChannel().name(), "status", n.getStatus().name())
                .increment();
    }
}
