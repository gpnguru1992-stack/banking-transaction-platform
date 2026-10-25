package com.bank.analytics;

import com.bank.common.event.EventEnvelope;
import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.PoisonMessageException;
import com.bank.common.observability.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.iceberg.catalog.Catalog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes Kafka events into the {@code events} and {@code transactions} Iceberg tables in micro-batches.
 * Each table keeps its own committed offsets, so if one commit succeeds and the other fails, the replay after
 * restart only fills the table that is behind. All methods run on the single listener thread.
 */
public class IcebergSink {

    private static final Logger log = LoggerFactory.getLogger(IcebergSink.class);

    private final IcebergTableWriter events;
    private final IcebergTableWriter transactions;
    private final EventCodec codec;
    private final EventRowMapper mapper;
    private final LakehouseProperties properties;
    private final Clock clock;
    private final Counter rowsWritten;
    private final Counter malformed;
    private final Counter skipped;
    private final Timer commitLatency;
    private Instant oldestBuffered;

    public IcebergSink(Catalog catalog, String consumerGroup, EventCodec codec, EventRowMapper mapper,
                       LakehouseProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.events = new IcebergTableWriter(LakehouseTables.loadOrCreate(catalog, properties.namespace(),
                LakehouseTables.EVENTS, LakehouseTables.EVENTS_SCHEMA), consumerGroup);
        this.transactions = new IcebergTableWriter(LakehouseTables.loadOrCreate(catalog, properties.namespace(),
                LakehouseTables.TRANSACTIONS, LakehouseTables.TRANSACTIONS_SCHEMA), consumerGroup);
        this.codec = codec;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.rowsWritten = meterRegistry.counter("banking.lakehouse.rows.written");
        this.malformed = meterRegistry.counter("banking.lakehouse.events.malformed");
        this.skipped = meterRegistry.counter("banking.lakehouse.events.already_committed");
        this.commitLatency = Timer.builder("banking.lakehouse.commit.latency").register(meterRegistry);
    }

    public void accept(ConsumerRecord<String, String> record) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        EventEnvelope envelope;
        try {
            envelope = codec.decode(record.value());
        } catch (PoisonMessageException e) {
            malformed.increment();
            log.warn("Skipping malformed event at {}@{}", tp, record.offset());
            events.add(null, tp, record.offset());
            transactions.add(null, tp, record.offset());
            return;
        }
        boolean newEvent = events.add(mapper.eventRow(envelope, record.topic(), record.partition(), record.offset()),
                tp, record.offset());
        boolean newTransaction = transactions.add(
                EventRowMapper.isClearingStep(envelope) ? null : mapper.transactionRow(envelope).orElse(null),
                tp, record.offset());
        if (!newEvent && !newTransaction) {
            skipped.increment();
        } else if (oldestBuffered == null) {
            oldestBuffered = clock.instant();
        }
    }

    public void flushIfDue() {
        boolean full = events.bufferedRows() >= properties.flushRows() || transactions.bufferedRows() >= properties.flushRows();
        boolean old = oldestBuffered != null && !clock.instant().isBefore(oldestBuffered.plus(properties.flushInterval()));
        if (full || old) {
            flush();
        }
    }

    public void flush() {
        if (!events.hasPendingOffsets() && !transactions.hasPendingOffsets()) {
            return;
        }
        commitLatency.record(() -> CorrelationId.runWith("lakehouse-flush", () -> {
            int eventRows = events.flush();
            int transactionRows = transactions.flush();
            rowsWritten.increment(eventRows + transactionRows);
            log.info("Committed {} event rows and {} transaction rows to Iceberg", eventRows, transactionRows);
        }));
        oldestBuffered = null;
    }

    /** Lowest committed offset per partition across both tables: where consumption must resume. */
    public Map<TopicPartition, Long> resumeOffsets() {
        Map<TopicPartition, Long> resume = new HashMap<>(events.committedOffsets());
        Map<TopicPartition, Long> tx = transactions.committedOffsets();
        resume.replaceAll((tp, offset) -> Math.min(offset, tx.getOrDefault(tp, -1L)));
        tx.forEach((tp, offset) -> resume.putIfAbsent(tp, -1L));
        return resume;
    }

    public void discardBuffered() {
        events.discard();
        transactions.discard();
        oldestBuffered = null;
    }
}
