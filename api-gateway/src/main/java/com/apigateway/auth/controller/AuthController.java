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
        return requestMono.flatMap(authService::login)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody Mono<RegisterRequest> requestMono) {
        return requestMono.flatMap(authService::register)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, String>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "UP", "service", "api-gateway-auth")));
    }

    // ── Authenticated endpoints ───────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> me(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .map(user -> ResponseEntity.ok(Map.of(
                        "email", authentication.getName(),
                        "role",  authentication.getAuthorities().iterator().next().getAuthority(),
                        "profilePicture", user.getProfilePicture() != null ? user.getProfilePicture() : "",
                        "username", user.getActualUsername()
                )))
                .defaultIfEmpty(ResponseEntity.ok(Map.of(
                        "email", authentication.getName(),
                        "role",  authentication.getAuthorities().iterator().next().getAuthority(),
                        "profilePicture", "",
                        "username", ""
                )));
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> updateProfile(
            Authentication authentication,
            @RequestBody Mono<Map<String, String>> bodyMono) {
        
        return bodyMono.flatMap(body -> 
            userRepository.findByEmail(authentication.getName())
                    .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                    .flatMap(user -> {
                        if (body.containsKey("profilePicture")) {
                            user.setProfilePicture(body.get("profilePicture"));
                        }

                        if (body.containsKey("username")) {
                            String newUsername = body.get("username").trim();
                            if (newUsername.length() < 3 || newUsername.length() > 30) {
                                return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "Username must be between 3 and 30 characters")));
                            }
                            if (!newUsername.matches("^[a-zA-Z0-9_]+$")) {
                                return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "Username may only contain letters, numbers, and underscores")));
                            }
                            if (!newUsername.equalsIgnoreCase(user.getActualUsername())) {
                                return userRepository.existsByUsername(newUsername).flatMap(exists -> {
                                    if (exists) {
                                        return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "Username is already taken")));
                                    }
                                    user.setUsername(newUsername);
                                    return userRepository.save(user).map(u -> ResponseEntity.ok(Map.of("message", "Profile updated successfully")));
                                });
                            }
                        }

                        return userRepository.save(user).map(u -> ResponseEntity.ok(Map.of("message", "Profile updated successfully")));
                    })
        );
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<LoginResponse>> refresh(@Valid @RequestBody Mono<RefreshTokenRequest> requestMono) {
        return requestMono.flatMap(authService::refresh)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody Mono<LogoutRequest> requestMono) {
        return requestMono.flatMap(request -> authService.logout(request.refreshToken(), authHeader))
                .thenReturn(ResponseEntity.ok(Map.of("message", "Logged out successfully")));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> changePassword(
            Authentication authentication,
            @Valid @RequestBody Mono<ChangePasswordRequest> requestMono) {
        return requestMono.flatMap(request -> authService.changePassword(authentication.getName(), request))
                .thenReturn(ResponseEntity.ok(Map.of("message", "Password changed successfully")));
    }
}
