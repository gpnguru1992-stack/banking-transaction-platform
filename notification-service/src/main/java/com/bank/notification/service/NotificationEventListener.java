package com.bank.notification.service;

import com.bank.common.event.CustomerEvent;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.FundsDepositedEvent;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.InboxService;
import com.bank.common.observability.CorrelationId;
import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.CustomerContactRepository;
import com.bank.notification.domain.Notification;
import com.bank.notification.domain.NotificationRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Component
public class NotificationEventListener {

    static final String CONSUMER = "notification-service";

    private final NotificationRepository notifications;
    private final CustomerContactRepository contacts;
    private final InboxService inbox;
    private final EventCodec codec;
    private final Clock clock;

    public NotificationEventListener(NotificationRepository notifications, CustomerContactRepository contacts,
                                     InboxService inbox, EventCodec codec, Clock clock) {
        this.notifications = notifications;
        this.contacts = contacts;
        this.inbox = inbox;
        this.codec = codec;
        this.clock = clock;
    }

    @KafkaListener(topics = {Topics.ACCOUNT_EVENTS, Topics.CUSTOMER_EVENTS}, groupId = "notification-service")
    @Transactional
    public void onMessage(String message) {
        EventEnvelope envelope = codec.decode(message);
        CorrelationId.runWith(envelope.correlationId(), () -> {
            if (inbox.markProcessed(envelope.eventId(), CONSUMER)) {
                handle(envelope);
            }
        });
    }

    private void handle(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case EventTypes.CUSTOMER_REGISTERED, EventTypes.CUSTOMER_UPDATED -> {
                CustomerEvent e = codec.payload(envelope, CustomerEvent.class);
                CustomerContact contact = contacts.findById(e.customerId()).orElseGet(() -> new CustomerContact(e.customerId()));
                if (contact.apply(e.fullName(), e.email(), e.phone(), e.emailOptIn(), e.smsOptIn(), e.occurredAt())) {
                    contacts.save(contact);
                }
            }
            case EventTypes.TRANSFER_COMPLETED -> {
                TransferCompletedEvent e = codec.payload(envelope, TransferCompletedEvent.class);
                notifyAll(envelope.eventId(), e.fromCustomerId(), MessageTemplates.debit(e.fromAccount(), e.toAccount(),
                        e.amount(), e.currency(), e.fromBalanceAfter()));
                notifyAll(envelope.eventId(), e.toCustomerId(), MessageTemplates.credit(e.toAccount(), e.fromAccount(),
                        e.amount(), e.currency(), e.toBalanceAfter()));
            }
            case EventTypes.TRANSFER_FAILED -> {
                TransferFailedEvent e = codec.payload(envelope, TransferFailedEvent.class);
                notifyAll(envelope.eventId(), e.customerId(), MessageTemplates.transferFailed(e.fromAccount(), e.amount(),
                        e.currency(), e.reason()));
            }
            case EventTypes.FUNDS_DEPOSITED -> {
                FundsDepositedEvent e = codec.payload(envelope, FundsDepositedEvent.class);
                notifyAll(envelope.eventId(), e.customerId(), MessageTemplates.deposit(e.accountNumber(), e.amount(),
                        e.currency(), e.balanceAfter()));
            }
            default -> {
                // not customer-facing
            }
        }
    }

    private void notifyAll(UUID eventId, String customerId, MessageTemplates.Message message) {
        for (Channel channel : Channel.values()) {
            notifications.save(new Notification(eventId, customerId, channel, message.subject(), message.body(),
                    CorrelationId.current(), clock.instant()));
        }
    }
}
