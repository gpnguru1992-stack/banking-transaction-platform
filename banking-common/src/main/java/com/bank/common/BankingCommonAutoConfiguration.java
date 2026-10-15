package com.bank.common;

import com.bank.common.messaging.EventCodec;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.messaging.InboxService;
import com.bank.common.messaging.OutboxProperties;
import com.bank.common.messaging.OutboxRelay;
import com.bank.common.messaging.OutboxRepository;
import com.bank.common.messaging.ProcessedMessageRepository;
import com.bank.common.web.CorrelationIdFilter;
import com.bank.common.web.GlobalExceptionHandler;
import com.bank.common.web.RequestContextPropagationInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@AutoConfigurationPackage(basePackages = "com.bank.common.messaging")
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class BankingCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>(new CorrelationIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    RestClientCustomizer requestContextPropagation() {
        return builder -> builder.requestInterceptor(new RequestContextPropagationInterceptor());
    }

    @Bean
    EventPublisher eventPublisher(OutboxRepository repository, ObjectMapper objectMapper, Clock clock,
                                  @Value("${spring.application.name}") String applicationName) {
        return new EventPublisher(repository, objectMapper, clock, applicationName);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                            PlatformTransactionManager transactionManager, OutboxProperties properties,
                            Clock clock, MeterRegistry meterRegistry) {
        return new OutboxRelay(repository, kafkaTemplate, new TransactionTemplate(transactionManager),
                properties, clock, meterRegistry);
    }

    @Bean
    InboxService inboxService(ProcessedMessageRepository repository, Clock clock) {
        return new InboxService(repository, clock);
    }

    @Bean
    EventCodec eventCodec(ObjectMapper objectMapper) {
        return new EventCodec(objectMapper);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(com.bank.common.messaging.KafkaErrorHandling.errorHandler(kafkaTemplate));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    OpenAPI bankingOpenApi(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI()
                .info(new Info().title(applicationName + " API").version("v1")
                        .description("Banking Transaction Platform - " + applicationName))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
