package com.bank.analytics;

import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.KafkaErrorHandling;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class LakehouseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventCodec eventCodec(ObjectMapper objectMapper) {
        return new EventCodec(objectMapper);
    }

    @Bean
    EventRowMapper eventRowMapper(EventCodec codec, @Value("${banking.business-zone:Asia/Kolkata}") ZoneId zone) {
        return new EventRowMapper(codec, zone);
    }

    /** Built from {@code banking.lakehouse.catalog.*}, e.g. NessieCatalog + S3FileIO on MinIO, or GlueCatalog on AWS. */
    @Bean(destroyMethod = "")
    Catalog lakehouseCatalog(LakehouseProperties properties) {
        return CatalogUtil.buildIcebergCatalog(properties.catalogName(), properties.catalog(),
                new org.apache.hadoop.conf.Configuration());
    }

    @Bean
    IcebergSink icebergSink(Catalog catalog, EventCodec codec, EventRowMapper mapper, LakehouseProperties properties,
                            Clock clock, MeterRegistry meterRegistry) {
        return new IcebergSink(catalog, EventsListener.GROUP, codec, mapper, properties, clock, meterRegistry);
    }

    /** Single-threaded batch consumer: one writer per instance; scale out by adding replicas (partitions split between them). */
    @Bean
    ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(KafkaErrorHandling.errorHandler(kafkaTemplate));
        return factory;
    }
}
