package com.bank.payment.clearing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP connector to the clearing network. The transfer id is the network reference, and the network is
 * idempotent on it, so resubmitting after a timeout can never pay twice.
 */
@Component
@EnableConfigurationProperties(ClearingProperties.class)
public class ClearingClient {

    public record Outcome(String status, String utr, String reasonCode, String reason, Instant decidedAt) {
        public boolean accepted() {
            return "ACCEPTED".equals(status);
        }
    }

    record Instruction(String reference, String rail, String remitterAccount, String beneficiaryName,
                       String beneficiaryAccount, String beneficiaryIfsc, BigDecimal amount, String currency) {
    }

    /** No usable answer: timeout, connection failure or a 5xx. The payment's outcome is unknown. */
    public static class ClearingUnavailableException extends RuntimeException {
        public ClearingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient restClient;

    public ClearingClient(RestClient.Builder builder, ClearingProperties properties) {
        this.restClient = builder.baseUrl(properties.url())
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout())))
                .build();
    }

    public Outcome submit(ClearingInstruction i) {
        try {
            return restClient.post().uri("/v1/clearing/payments")
                    .body(new Instruction(i.getTransferId().toString(), i.getRail().name(), i.getFromAccount(),
                            i.getBeneficiaryName(), i.getBeneficiaryAccount(), i.getBeneficiaryIfsc(), i.getAmount(),
                            i.getCurrency()))
                    .retrieve()
                    .body(Outcome.class);
        } catch (HttpClientErrorException e) {
            return new Outcome("REJECTED", null, "CLEARING_REQUEST_REJECTED",
                    "Clearing network refused the instruction (" + e.getStatusCode().value() + ")", Instant.now());
        } catch (RestClientException e) {
            throw new ClearingUnavailableException("Submit failed: " + e.getMessage(), e);
        }
    }

    /** Status inquiry; empty when the network never received the instruction. */
    public Optional<Outcome> inquire(UUID transferId) {
        try {
            return Optional.ofNullable(restClient.get().uri("/v1/clearing/payments/{ref}", transferId)
                    .retrieve().body(Outcome.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ClearingUnavailableException("Inquiry failed: " + e.getMessage(), e);
        }
    }
}
