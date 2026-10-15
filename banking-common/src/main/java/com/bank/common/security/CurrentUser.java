package com.bank.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Accessors for the authenticated principal. The {@code customer_id} claim links a login to a customer. */
public final class CurrentUser {

    public static final String CUSTOMER_ID_CLAIM = "customer_id";

    private CurrentUser() {
    }

    public static String customerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String id = jwt.getToken().getClaimAsString(CUSTOMER_ID_CLAIM);
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        throw new AccessDeniedException("Token has no customer_id claim");
    }

    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    public static boolean isStaff() {
        return hasRole(Roles.ADMIN) || hasRole(Roles.OPERATIONS);
    }
}
