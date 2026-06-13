package com.authservice.dto;

import com.authservice.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @Size(min = 3, max = 30, message = "Username must be 3–30 characters")
            @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username may only contain letters, numbers, and underscores")
            String username,

            @NotBlank
            @Size(min = 8, message = "Password must be at least 8 characters")
            String password,

            @NotNull(message = "Role is required")
            Role role
    ) {}

    // ── Outbound ──────────────────────────────────────────────────────────────

    public record LoginResponse(
            String token,
            String refreshToken,
            String userId,
            String email,
            String role
    ) {}

    public record RegisterResponse(
            String userId,
            String email,
            String username,
            String role
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

    public record RefreshTokenRequest(
            @NotBlank
            String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank
            String refreshToken
    ) {}


    public record ChangePasswordRequest(
            @NotBlank
            String currentPassword,

            @NotBlank
            @Size(min = 8, message = "New password must be at least 8 characters")
            String newPassword
    ) {}
}