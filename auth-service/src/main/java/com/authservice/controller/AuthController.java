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



  @PostMapping("/refresh")
  public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(authService.refresh(request));
  }

  @PostMapping("/logout")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, String>> logout(
          @RequestHeader(value = "Authorization", required = false) String authHeader,
          @Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken(), authHeader);
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