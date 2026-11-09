package com.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// ── Inbound ───────────────────────────────────────────────────────────────────

public class AuthDtos {

    public record LoginRequest(
            @Email(message = "Must be a valid email")
            @NotBlank
            String email,

            @NotBlank
            String password
    ) {}

    public record RegisterRequest(
            @Email(message = "Must be a valid email")
            @NotBlank
            String email,

            @NotBlank
            @Size(min = 3, max = 50, message = "Username must be 3–50 characters")
            @Pattern(regexp = "^[a-zA-Z0-9_@.-]+$", message = "Username may only contain letters, numbers, underscores, @, ., and -")
            String username,

            @NotBlank
            @Size(min = 8, message = "Password must be at least 8 characters")
            String password,

            @Size(min = 3, max = 3, message = "Default currency must be a 3-letter ISO code")
            String defaultCurrency
            // Role is deliberately absent: self-registration always creates a
            // USER. Accepting a client-supplied role is privilege escalation
            // waiting for the enum to grow.
    ) {}

    // ── Outbound ──────────────────────────────────────────────────────────────

    public record LoginResponse(
            String token,
            String refreshToken,
            String userId,
            String email,
            String username,
            String role,
            String defaultCurrency
    ) {}

    public record RegisterResponse(
            String userId,
            String email,
            String username,
            String role,
            String defaultCurrency
    ) {}

    public record UserSearchResult(
            String userId,
            String username,
            String email,
            String profilePicture
    ) {}

    public record ErrorResponse(
            String error,
            String message
    ) {}

    // ── Additional Inbound ─────────────────────────────────────────────────────

    // The refresh credential now travels in an HttpOnly cookie. The body field
    // is accepted only for non-browser API clients and is optional.
    public record RefreshTokenRequest(
            String refreshToken
    ) {}

    public record LogoutRequest(
            String refreshToken
    ) {}

    /** Short-lived credential for EventSource, which cannot send a header. */
    public record StreamTicketResponse(
            String ticket
    ) {}


    public record ChangePasswordRequest(
            @NotBlank
            String currentPassword,

            @NotBlank
            @Size(min = 8, message = "New password must be at least 8 characters")
            String newPassword
    ) {}
}
