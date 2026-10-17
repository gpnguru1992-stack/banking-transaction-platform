package com.bank.account.service;

import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.Topics;
import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.InboxService;
import com.bank.common.observability.CorrelationId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies clearing-network outcomes from payment-service: finalise (SETTLED) or refund (REJECTED). */
@Component
public class PaymentEventsListener {

    static final String CONSUMER = "account-service.payment-events";

    private final InterbankTransferService service;
    private final InboxService inbox;
    private final EventCodec codec;

    public PaymentEventsListener(InterbankTransferService service, InboxService inbox, EventCodec codec) {
        this.service = service;
        this.inbox = inbox;
        this.codec = codec;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "account-service")
    @Transactional
    public void onMessage(String message) {
        EventEnvelope envelope = codec.decode(message);
        boolean settled = EventTypes.CLEARING_SETTLED.equals(envelope.eventType());
        boolean rejected = EventTypes.CLEARING_REJECTED.equals(envelope.eventType());
        if (!settled && !rejected) {
            return;
        }
        CorrelationId.runWith(envelope.correlationId(), () -> {
            if (inbox.markProcessed(envelope.eventId(), CONSUMER)) {
                service.applyClearingResult(codec.payload(envelope, ClearingResultEvent.class), settled);
            }
        });
    }
}
