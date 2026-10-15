package com.bank.account.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
                .requestInterceptor(forwardCallerToken())
                .build();
    }

    /** Propagates the caller's JWT so customer-service authorises the call with the same identity. */
    static ClientHttpRequestInterceptor forwardCallerToken() {
        return (request, body, execution) -> {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
                request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
            }
            return execution.execute(request, body);
        };
    }
}
