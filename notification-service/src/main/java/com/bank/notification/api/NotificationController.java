package com.bank.notification.api;

import com.bank.common.security.CurrentUser;
import com.bank.notification.domain.Channel;
import com.bank.notification.domain.Notification;
import com.bank.notification.domain.NotificationRepository;
import com.bank.notification.domain.NotificationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Notifications sent to the logged-in customer, newest first")
    public PageResponse mine(@RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = repository.findByCustomerIdOrderByCreatedAtDesc(CurrentUser.customerId(), PageRequest.of(page, size));
        return new PageResponse(result.map(NotificationResponse::from).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }

    public record NotificationResponse(UUID id, Channel channel, String subject, String body,
                                       NotificationStatus status, int attempts, Instant createdAt, Instant sentAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getChannel(), n.getSubject(), n.getBody(), n.getStatus(),
                    n.getAttempts(), n.getCreatedAt(), n.getSentAt());
        }
    }

    public record PageResponse(List<NotificationResponse> content, int page, int size, long totalElements) {
    }
}
