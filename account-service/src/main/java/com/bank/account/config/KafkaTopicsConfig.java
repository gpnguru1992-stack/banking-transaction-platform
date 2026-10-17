package com.bank.account.config;

import com.bank.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    /** Keyed by source account number, so all events of one account stay ordered on one partition. */
    @Bean
    NewTopic accountEventsTopic(@Value("${banking.kafka.partitions:6}") int partitions,
                                @Value("${banking.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.ACCOUNT_EVENTS).partitions(partitions).replicas(replicas)
                .config("min.insync.replicas", String.valueOf(Math.min(2, replicas)))
                .build();
    }

    @Bean
    NewTopic paymentEventsDlt(@Value("${banking.kafka.partitions:6}") int partitions,
                              @Value("${banking.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS + Topics.DLT_SUFFIX).partitions(partitions).replicas(replicas).build();
    }
}
