package com.apigateway.auth.controller;

import com.apigateway.auth.dto.AuthDtos.*;
import com.apigateway.auth.model.User;
import com.apigateway.auth.repository.UserRepository;
import com.apigateway.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    // ── Public endpoints ──────────────────────────────────────────────────────

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody Mono<LoginRequest> requestMono) {
        return requestMono.publishOn(Schedulers.boundedElastic())
                .map(request -> ResponseEntity.ok(authService.login(request)));
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody Mono<RegisterRequest> requestMono) {
        return requestMono.publishOn(Schedulers.boundedElastic())
                .map(request -> ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request)));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, String>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "UP", "service", "api-gateway-auth")));
    }

    // ── Authenticated endpoints ───────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> me(Authentication authentication) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(authentication.getName()).orElse(null);
            return ResponseEntity.ok(Map.of(
                    "email", authentication.getName(),
                    "role",  authentication.getAuthorities().iterator().next().getAuthority(),
                    "profilePicture", user != null && user.getProfilePicture() != null ? user.getProfilePicture() : "",
                    "username", user != null ? user.getActualUsername() : ""
            ));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> updateProfile(
            Authentication authentication,
            @RequestBody Mono<Map<String, String>> bodyMono) {
        
        return bodyMono.publishOn(Schedulers.boundedElastic()).map(body -> {
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
        });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<LoginResponse>> refresh(@Valid @RequestBody Mono<RefreshTokenRequest> requestMono) {
        return requestMono.publishOn(Schedulers.boundedElastic())
                .map(request -> ResponseEntity.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody Mono<LogoutRequest> requestMono) {
        return requestMono.publishOn(Schedulers.boundedElastic())
                .map(request -> {
                    authService.logout(request.refreshToken(), authHeader);
                    return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
                });
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> changePassword(
            Authentication authentication,
            @Valid @RequestBody Mono<ChangePasswordRequest> requestMono) {
        return requestMono.publishOn(Schedulers.boundedElastic())
                .map(request -> {
                    authService.changePassword(authentication.getName(), request);
                    return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
                });
    }
}
