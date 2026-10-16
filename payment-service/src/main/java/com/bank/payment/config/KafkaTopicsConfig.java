package com.bank.payment.config;

import com.bank.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic accountEventsDlt(@Value("${banking.kafka.partitions:6}") int partitions,
                              @Value("${banking.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.ACCOUNT_EVENTS + Topics.DLT_SUFFIX).partitions(partitions).replicas(replicas).build();
    }
}
