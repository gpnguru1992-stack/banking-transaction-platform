package com.bank.account.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    /** Tight timeouts: a slow dependency must fail fast and trip the circuit breaker, not pile up threads. */
    @Bean
    RestClient customerServiceRestClient(RestClient.Builder builder,
                                         @Value("${banking.clients.customer-service.url}") String baseUrl,
                                         @Value("${banking.clients.customer-service.connect-timeout:2s}") Duration connectTimeout,
                                         @Value("${banking.clients.customer-service.read-timeout:3s}") Duration readTimeout) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout)))
                .build();
    }
}
