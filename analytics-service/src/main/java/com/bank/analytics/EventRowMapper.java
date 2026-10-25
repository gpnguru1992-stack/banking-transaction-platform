package com.bank.analytics;

import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.FundsDepositedEvent;
import com.bank.common.event.InterbankTransferEvent;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.event.TransferFailedEvent;
import com.bank.common.messaging.EventCodec;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/** Turns event envelopes into Iceberg rows. Only money-movement events produce a {@code transactions} row. */
public class EventRowMapper {

    private final EventCodec codec;
    private final ZoneId businessZone;

    public EventRowMapper(EventCodec codec, ZoneId businessZone) {
        this.codec = codec;
        this.businessZone = businessZone;
    }

    public Record eventRow(EventEnvelope e, String topic, int partition, long offset) {
        Record r = GenericRecord.create(LakehouseTables.EVENTS_SCHEMA);
        r.setField("event_id", e.eventId().toString());
        r.setField("event_type", e.eventType());
        r.setField("aggregate_type", e.aggregateType());
        r.setField("aggregate_id", e.aggregateId());
        r.setField("producer", e.producer());
        r.setField("correlation_id", e.correlationId());
        r.setField("topic", topic);
        r.setField("kafka_partition", partition);
        r.setField("kafka_offset", offset);
        r.setField("occurred_at", utc(e.occurredAt()));
        r.setField("payload", e.payload().toString());
        return r;
    }

    public Optional<Record> transactionRow(EventEnvelope e) {
        return switch (e.eventType()) {
            case EventTypes.TRANSFER_COMPLETED -> {
                TransferCompletedEvent p = codec.payload(e, TransferCompletedEvent.class);
                yield Optional.of(row(e, p.transferId().toString(), "TRANSFER", "COMPLETED", null, p.fromCustomerId(),
                        p.fromAccount(), p.toAccount(), null, p.amount(), p.currency(), null, null));
            }
            case EventTypes.TRANSFER_FAILED -> {
                TransferFailedEvent p = codec.payload(e, TransferFailedEvent.class);
                String type = "InterbankTransfer".equals(e.aggregateType()) ? "INTERBANK" : "TRANSFER";
                yield Optional.of(row(e, p.transferId().toString(), type, "FAILED", null, p.customerId(),
                        p.fromAccount(), p.toAccount(), null, p.amount(), p.currency(), null, p.reasonCode()));
            }
            case EventTypes.FUNDS_DEPOSITED -> {
                FundsDepositedEvent p = codec.payload(e, FundsDepositedEvent.class);
                yield Optional.of(row(e, p.depositId().toString(), "DEPOSIT", "COMPLETED", null, p.customerId(),
                        null, p.accountNumber(), null, p.amount(), p.currency(), null, null));
            }
            case EventTypes.INTERBANK_TRANSFER_INITIATED -> Optional.of(interbank(e, "PENDING"));
            case EventTypes.INTERBANK_TRANSFER_SETTLED -> Optional.of(interbank(e, "SETTLED"));
            case EventTypes.INTERBANK_TRANSFER_REFUNDED -> Optional.of(interbank(e, "REFUNDED"));
            default -> Optional.empty();
        };
    }

    /** Clearing results are internal steps; the account-events Settled/Refunded rows already carry the outcome. */
    static boolean isClearingStep(EventEnvelope e) {
        return EventTypes.CLEARING_SETTLED.equals(e.eventType()) || EventTypes.CLEARING_REJECTED.equals(e.eventType());
    }

    private Record interbank(EventEnvelope e, String status) {
        InterbankTransferEvent p = codec.payload(e, InterbankTransferEvent.class);
        return row(e, p.transferId().toString(), "INTERBANK", status, p.rail().name(), p.customerId(), p.fromAccount(),
                p.beneficiaryAccount(), p.beneficiaryIfsc(), p.amount(), p.currency(), p.utr(), p.reasonCode());
    }

    private Record row(EventEnvelope e, String referenceId, String type, String status, String rail, String customerId,
                       String from, String to, String ifsc, BigDecimal amount, String currency, String utr,
                       String failureCode) {
        Record r = GenericRecord.create(LakehouseTables.TRANSACTIONS_SCHEMA);
        r.setField("event_id", e.eventId().toString());
        r.setField("reference_id", referenceId);
        r.setField("event_type", e.eventType());
        r.setField("transaction_type", type);
        r.setField("status", status);
        r.setField("rail", rail);
        r.setField("customer_id", customerId);
        r.setField("from_account", from);
        r.setField("to_account", to);
        r.setField("counterparty_ifsc", ifsc);
        r.setField("amount", amount.setScale(2, RoundingMode.UNNECESSARY));
        r.setField("currency", currency);
        r.setField("utr", utr);
        r.setField("failure_code", failureCode);
        r.setField("correlation_id", e.correlationId());
        r.setField("occurred_at", utc(e.occurredAt()));
        r.setField("business_date", LocalDate.ofInstant(e.occurredAt(), businessZone));
        return r;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
