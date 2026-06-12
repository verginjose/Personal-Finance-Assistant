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

import com.authservice.repository.UserRepository;
import com.authservice.model.User;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final UserRepository userRepository;

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
    User user = userRepository.findByEmail(authentication.getName()).orElse(null);
    return ResponseEntity.ok(Map.of(
            "email", authentication.getName(),
            "role",  authentication.getAuthorities()
                    .iterator().next().getAuthority(),
            "profilePicture", user != null && user.getProfilePicture() != null ? user.getProfilePicture() : "",
            "username", user != null ? user.getActualUsername() : ""
    ));
  }

  @PutMapping("/profile")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<String, String>> updateProfile(
          Authentication authentication,
          @RequestBody Map<String, String> body) {
    User user = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new RuntimeException("User not found"));
            
    if (body.containsKey("profilePicture")) {
        user.setProfilePicture(body.get("profilePicture"));
    }
    
    if (body.containsKey("username")) {
        String newUsername = body.get("username").trim();
        if (newUsername.length() < 3 || newUsername.length() > 30) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username must be between 3 and 30 characters"));
        }
        if (!newUsername.matches("^[a-zA-Z0-9_]+$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username may only contain letters, numbers, and underscores"));
        }
        if (!newUsername.equalsIgnoreCase(user.getActualUsername()) && userRepository.existsByUsername(newUsername)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username is already taken"));
        }
        user.setUsername(newUsername);
    }
    
    userRepository.save(user);
    return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
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