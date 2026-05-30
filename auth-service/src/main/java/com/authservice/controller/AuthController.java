package com.authservice.controller;

import com.authservice.dto.AuthDtos.*;
import com.authservice.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  // ── Public endpoints ──────────────────────────────────────────────────────

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }

  @PostMapping("/register")
  public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(authService.register(request));
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of("status", "UP", "service", "auth-service"));
  }

  // ── Authenticated: any valid token ────────────────────────────────────────

  /**
   * Returns the currently authenticated user's info.
   * @AuthenticationPrincipal injects the principal set by JwtAuthenticationFilter.
   */
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
    return ResponseEntity.ok(Map.of(
            "email", authentication.getName(),
            "role",  authentication.getAuthorities()
                    .iterator().next().getAuthority()  // "ROLE_USER"
    ));
  }

  // ── MODERATOR or ADMIN only ───────────────────────────────────────────────

  @GetMapping("/moderation/dashboard")
  @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
  public ResponseEntity<Map<String, String>> moderationDashboard() {
    return ResponseEntity.ok(Map.of("message", "Welcome to the moderation dashboard"));
  }

  // ── ADMIN only ────────────────────────────────────────────────────────────

  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, String>> adminUsers() {
    return ResponseEntity.ok(Map.of("message", "Admin: full user list would be here"));
  }

  @PostMapping("/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RegisterResponse> adminCreateUser(@Valid @RequestBody AdminCreateUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(authService.adminCreateUser(request));
  }

  @DeleteMapping("/admin/users/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
    // Admin-only delete logic would go here
    return ResponseEntity.noContent().build();
  }

  // ── Extra public / authenticated mappings ───────────────────────────────

  @PostMapping("/refresh")
  public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(authService.refresh(request));
  }

  @PostMapping("/logout")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, String>> logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
  }

  @PostMapping("/change-password")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, String>> changePassword(
          Authentication authentication,
          @Valid @RequestBody ChangePasswordRequest request) {
    authService.changePassword(authentication.getName(), request);
    return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
  }
}