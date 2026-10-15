package com.bank.transaction.service;

import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.FundsDepositedEvent;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.InboxService;
import com.bank.common.observability.CorrelationId;
import com.bank.transaction.domain.AuditLog;
import com.bank.transaction.domain.AuditLogRepository;
import com.bank.transaction.domain.TransactionRecord;
import com.bank.transaction.domain.TransactionRecordRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Builds transaction history and the audit trail. Every event on every topic is audited; money
 * movements are also projected into per-account statement lines. Idempotent via the inbox.
 */
@Component
public class EventProjector {

    static final String CONSUMER = "transaction-service";
    private static final Logger log = LoggerFactory.getLogger(EventProjector.class);

    private final TransactionRecordRepository transactions;
    private final AuditLogRepository auditLogs;
    private final InboxService inbox;
    private final EventCodec codec;
    private final Clock clock;

    public EventProjector(TransactionRecordRepository transactions, AuditLogRepository auditLogs, InboxService inbox,
                          EventCodec codec, Clock clock) {
        this.transactions = transactions;
        this.auditLogs = auditLogs;
        this.inbox = inbox;
        this.codec = codec;
        this.clock = clock;
    }

    @KafkaListener(topics = {Topics.ACCOUNT_EVENTS, Topics.CUSTOMER_EVENTS}, groupId = "transaction-service")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = codec.decode(record.value());
        CorrelationId.runWith(envelope.correlationId(), () -> {
            if (!inbox.markProcessed(envelope.eventId(), CONSUMER)) {
                return;
            }
            auditLogs.save(new AuditLog(envelope.eventId(), envelope.eventType(), envelope.aggregateType(),
                    envelope.aggregateId(), envelope.producer(), envelope.correlationId(), record.topic(),
                    envelope.payload().toString(), envelope.occurredAt(), clock.instant()));
            project(envelope);
        });
    }

    private void project(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case EventTypes.TRANSFER_COMPLETED -> {
                TransferCompletedEvent e = codec.payload(envelope, TransferCompletedEvent.class);
                String description = e.reference() == null ? "Transfer" : e.reference();
                transactions.save(new TransactionRecord(e.transferId(), e.fromAccount(), e.fromCustomerId(),
                        e.toAccount(), "TRANSFER", "DEBIT", e.amount(), e.currency(), e.fromBalanceAfter(),
                        "COMPLETED", description, e.completedAt()));
                transactions.save(new TransactionRecord(e.transferId(), e.toAccount(), e.toCustomerId(),
                        e.fromAccount(), "TRANSFER", "CREDIT", e.amount(), e.currency(), e.toBalanceAfter(),
                        "COMPLETED", description, e.completedAt()));
            }
            case EventTypes.TRANSFER_FAILED -> {
                TransferFailedEvent e = codec.payload(envelope, TransferFailedEvent.class);
                transactions.save(new TransactionRecord(e.transferId(), e.fromAccount(), e.customerId(),
                        e.toAccount(), "TRANSFER", "DEBIT", e.amount(), e.currency(), null, "FAILED",
                        e.reasonCode() + ": " + e.reason(), e.failedAt()));
            }
            case EventTypes.FUNDS_DEPOSITED -> {
                FundsDepositedEvent e = codec.payload(envelope, FundsDepositedEvent.class);
                transactions.save(new TransactionRecord(e.depositId(), e.accountNumber(), e.customerId(), null,
                        "DEPOSIT", "CREDIT", e.amount(), e.currency(), e.balanceAfter(), "COMPLETED", e.reference(),
                        e.depositedAt()));
            }
            default -> log.debug("Audited {} without projection", envelope.eventType());
        }
    }
}
