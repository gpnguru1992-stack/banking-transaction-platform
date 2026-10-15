package com.bank.common.messaging;

import com.bank.common.event.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

public final class KafkaErrorHandling {

    private KafkaErrorHandling() {
    }

    /**
     * Retries transient failures with exponential back-off, then parks the record on
     * {@code <topic>.DLT} (same partition) so one bad message never blocks the partition.
     */
    public static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonMessageException.class, IllegalArgumentException.class);
        return handler;
    }
}
