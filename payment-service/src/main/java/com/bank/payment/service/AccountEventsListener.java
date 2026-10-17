package com.bank.payment.service;

import com.bank.common.event.InterbankTransferEvent;
import com.bank.payment.clearing.ClearingDispatcher;
import com.bank.payment.clearing.ClearingInstruction;
import com.bank.payment.clearing.ClearingInstructionRepository;
import com.bank.payment.clearing.ClearingProperties;
import java.time.Instant;
import java.util.UUID;

import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.InboxService;
import com.bank.common.observability.CorrelationId;
import com.bank.payment.domain.Payment;
import com.bank.payment.domain.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class AccountEventsListener {

    static final String CONSUMER = "payment-service.account-events";
    private static final Logger log = LoggerFactory.getLogger(AccountEventsListener.class);

    private final PaymentRepository repository;
    private final InboxService inbox;
    private final EventCodec codec;
    private final Clock clock;
    private final ZoneId zone;
    private final MeterRegistry meterRegistry;
    private final ClearingInstructionRepository clearing;
    private final ClearingProperties clearingProperties;

    public AccountEventsListener(PaymentRepository repository, InboxService inbox, EventCodec codec, Clock clock,
                                 @Value("${banking.business-zone:Asia/Kolkata}") ZoneId zone, MeterRegistry meterRegistry,
                                 ClearingInstructionRepository clearing, ClearingProperties clearingProperties) {
        this.clearing = clearing;
        this.clearingProperties = clearingProperties;
        this.repository = repository;
        this.inbox = inbox;
        this.codec = codec;
        this.clock = clock;
        this.zone = zone;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = Topics.ACCOUNT_EVENTS, groupId = "payment-service")
    @Transactional
    public void onMessage(String message) {
        EventEnvelope envelope = codec.decode(message);
        CorrelationId.runWith(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope envelope) {
        if (EventTypes.INTERBANK_TRANSFER_INITIATED.equals(envelope.eventType())) {
            queueForClearing(envelope);
            return;
        }
        boolean relevant = EventTypes.TRANSFER_COMPLETED.equals(envelope.eventType())
                || EventTypes.TRANSFER_FAILED.equals(envelope.eventType());
        if (!relevant || !inbox.markProcessed(envelope.eventId(), CONSUMER)) {
            return;
        }
        LocalDate businessDate = LocalDate.ofInstant(envelope.occurredAt(), zone);
        if (EventTypes.TRANSFER_COMPLETED.equals(envelope.eventType())) {
            TransferCompletedEvent e = codec.payload(envelope, TransferCompletedEvent.class);
            repository.save(Payment.completed(e.transferId(), e.fromCustomerId(), e.toCustomerId(), e.fromAccount(),
                    e.toAccount(), e.amount(), e.currency(), e.reference(), businessDate, e.completedAt(), clock.instant()));
        } else {
            TransferFailedEvent e = codec.payload(envelope, TransferFailedEvent.class);
            repository.save(Payment.failed(e.transferId(), e.customerId(), e.fromAccount(), e.toAccount(), e.amount(),
                    e.currency(), e.reasonCode(), e.reason(), businessDate, e.failedAt(), clock.instant()));
        }
        meterRegistry.counter("banking.payments.recorded", "type", envelope.eventType()).increment();
        log.info("Recorded payment for transfer {} ({})", envelope.aggregateId(), envelope.eventType());
    }

    private void queueForClearing(EventEnvelope envelope) {
        if (!inbox.markProcessed(envelope.eventId(), CONSUMER) || clearing.existsById(UUID.fromString(envelope.aggregateId()))) {
            return;
        }
        InterbankTransferEvent e = codec.payload(envelope, InterbankTransferEvent.class);
        Instant now = clock.instant();
        clearing.save(new ClearingInstruction(e, envelope.correlationId(),
                ClearingDispatcher.firstAttemptAt(e.rail(), now, clearingProperties.neftBatchInterval()), now));
        meterRegistry.counter("banking.clearing.queued", "rail", e.rail().name()).increment();
        log.info("Queued interbank transfer {} for {} clearing", e.transferId(), e.rail());
    }
}
