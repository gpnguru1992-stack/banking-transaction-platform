package com.bank.analytics;

import com.bank.common.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Batch consumer of account-events and payment-events. On assignment it seeks to the offsets stored in Iceberg
 * (not Kafka's committed offsets), which is what makes the sink exactly-once. customer-events is not consumed:
 * it carries personal data (email, phone) that does not belong in the analytics lake.
 */
@Component
public class EventsListener implements ConsumerSeekAware {

    static final String GROUP = "analytics-service";
    private static final Logger log = LoggerFactory.getLogger(EventsListener.class);

    private final IcebergSink sink;

    public EventsListener(IcebergSink sink) {
        this.sink = sink;
    }

    @KafkaListener(topics = {Topics.ACCOUNT_EVENTS, Topics.PAYMENT_EVENTS}, groupId = GROUP, batch = "true")
    public synchronized void onBatch(List<ConsumerRecord<String, String>> records) {
        records.forEach(sink::accept);
        sink.flushIfDue();
    }

    @Override
    public synchronized void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        Map<TopicPartition, Long> committed = sink.resumeOffsets();
        assignments.keySet().forEach(tp -> {
            Long offset = committed.get(tp);
            if (offset != null && offset >= 0) {
                callback.seek(tp.topic(), tp.partition(), offset + 1);
                log.info("Resuming {} from Iceberg offset {}", tp, offset + 1);
            }
        });
    }

    @Override
    public synchronized void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        try {
            sink.flush();
        } catch (RuntimeException e) {
            log.warn("Flush on rebalance failed; buffered rows will be re-read by the next owner", e);
            sink.discardBuffered();
        }
    }

    @Override
    public synchronized void onIdleContainer(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        sink.flushIfDue();
    }
}
