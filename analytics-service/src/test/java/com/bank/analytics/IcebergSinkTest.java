package com.bank.analytics;

import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankTransferEvent;
import com.bank.common.event.PaymentRail;
import com.bank.common.event.Topics;
import com.bank.common.event.TransferCompletedEvent;
import com.bank.common.messaging.EventCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Writes real Parquet files through Iceberg (in-memory catalog and file IO) and reads them back. */
class IcebergSinkTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final EventCodec codec = new EventCodec(mapper);
    private final LakehouseProperties properties = new LakehouseProperties(null, "banking", Map.of(), 1000, null);
    private InMemoryCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        catalog.initialize("test", Map.of());
    }

    private IcebergSink newSink() {
        return new IcebergSink(catalog, "analytics-service", codec, new EventRowMapper(codec, ZoneId.of("Asia/Kolkata")),
                properties, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    private ConsumerRecord<String, String> record(String topic, long offset, String type, String aggregateType, Object payload)
            throws Exception {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type, 1, aggregateType, UUID.randomUUID().toString(),
                "corr-" + offset, "account-service", Instant.parse("2026-10-15T18:45:00Z"), mapper.valueToTree(payload));
        return new ConsumerRecord<>(topic, 0, offset, "ACC1001", mapper.writeValueAsString(envelope));
    }

    private ConsumerRecord<String, String> transfer(long offset) throws Exception {
        return record(Topics.ACCOUNT_EVENTS, offset, EventTypes.TRANSFER_COMPLETED, "Transfer",
                new TransferCompletedEvent(UUID.randomUUID(), "ACC1001", "CUST1001", "ACC2001", "CUST1002",
                        new BigDecimal("10000"), "INR", new BigDecimal("90000"), new BigDecimal("35000"), "Rent",
                        Instant.parse("2026-10-15T18:45:00Z")));
    }

    private List<Record> rows(String table) throws Exception {
        Table t = catalog.loadTable(TableIdentifier.of(Namespace.of("banking"), table));
        List<Record> out = new ArrayList<>();
        try (CloseableIterable<Record> it = IcebergGenerics.read(t).build()) {
            it.forEach(out::add);
        }
        return out;
    }

    @Test
    void writesEventsAndTransactionsWithBusinessDateInIndia() throws Exception {
        IcebergSink sink = newSink();
        sink.accept(transfer(0));
        sink.accept(record(Topics.ACCOUNT_EVENTS, 1, EventTypes.INTERBANK_TRANSFER_INITIATED, "InterbankTransfer",
                new InterbankTransferEvent(UUID.randomUUID(), "CUST1001", "ACC1001", "Meena Iyer", "123456789012",
                        "HDFC0001234", PaymentRail.IMPS, new BigDecimal("5000"), "INR", new BigDecimal("85000"), null,
                        null, null, null, Instant.parse("2026-10-15T18:45:00Z"))));
        sink.accept(record(Topics.PAYMENT_EVENTS, 0, EventTypes.CLEARING_SETTLED, "InterbankTransfer", Map.of("utr", "X")));
        sink.flush();

        assertThat(rows(LakehouseTables.EVENTS)).hasSize(3);
        List<Record> tx = rows(LakehouseTables.TRANSACTIONS);
        assertThat(tx).hasSize(2);
        Record interbank = tx.stream().filter(r -> "INTERBANK".equals(r.getField("transaction_type"))).findFirst().orElseThrow();
        assertThat(interbank.getField("status")).isEqualTo("PENDING");
        assertThat(interbank.getField("rail")).isEqualTo("IMPS");
        assertThat((BigDecimal) interbank.getField("amount")).isEqualByComparingTo("5000.00");
        assertThat(interbank.getField("business_date").toString()).isEqualTo("2026-10-16");
    }

    @Test
    void restartResumesAfterCommittedOffsetAndNeverDuplicates() throws Exception {
        IcebergSink first = newSink();
        first.accept(transfer(0));
        first.accept(transfer(1));
        first.flush();
        first.accept(transfer(2));
        // crash before flush: offset 2 is lost from the buffer, not from Kafka

        IcebergSink restarted = newSink();
        assertThat(restarted.resumeOffsets()).containsEntry(new TopicPartition(Topics.ACCOUNT_EVENTS, 0), 1L);
        restarted.accept(transfer(1)); // replayed duplicate
        restarted.accept(transfer(2));
        restarted.flush();

        assertThat(rows(LakehouseTables.TRANSACTIONS)).hasSize(3);
        assertThat(rows(LakehouseTables.EVENTS)).extracting(r -> r.getField("kafka_offset"))
                .containsExactlyInAnyOrder(0L, 1L, 2L);
    }

    @Test
    void malformedEventIsSkippedButItsOffsetIsCommitted() throws Exception {
        IcebergSink sink = newSink();
        sink.accept(new ConsumerRecord<>(Topics.ACCOUNT_EVENTS, 0, 0, "k", "not json"));
        sink.accept(transfer(1));
        sink.flush();

        assertThat(rows(LakehouseTables.EVENTS)).hasSize(1);
        assertThat(newSink().resumeOffsets()).containsEntry(new TopicPartition(Topics.ACCOUNT_EVENTS, 0), 1L);
    }
}
