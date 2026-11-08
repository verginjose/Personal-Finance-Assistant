package com.authservice.controller;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "pfa_refresh";

    private final AuthService authService;
    private final UserRepository userRepository;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    /** Set true in production (the browser talks HTTPS through the edge). */
    @Value("${security.cookie.secure:false}")
    private boolean cookieSecure;

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    /** Never echo the refresh credential to JavaScript. */
    private static LoginResponse withoutRefresh(LoginResponse r) {
        return new LoginResponse(r.token(), null, r.userId(), r.email(),
                r.username(), r.role(), r.defaultCurrency());
    }

    // ── Public endpoints ──────────────────────────────────────────────────────

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody Mono<LoginRequest> requestMono) {
        return requestMono.flatMap(authService::login)
                .map(response -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, refreshCookie(response.refreshToken()).toString())
                        .body(withoutRefresh(response)));
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody Mono<RegisterRequest> requestMono) {
        return requestMono.flatMap(authService::register)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, String>>> health() {
        return Mono.just(ResponseEntity.ok(Map.of("status", "UP", "service", "auth-service")));
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
                        "username", user.getActualUsername(),
                        "defaultCurrency", user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "INR"
                )))
                .defaultIfEmpty(ResponseEntity.ok(Map.of(
                        "email", authentication.getName(),
                        "role",  authentication.getAuthorities().iterator().next().getAuthority(),
                        "profilePicture", "",
                        "username", "",
                        "defaultCurrency", "INR"
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
                            String picture = body.get("profilePicture");
                            picture = picture == null ? "" : picture.trim();
                            // Only accept a URL this app produced. Rejecting
                            // quotes and other markup stops the value from ever
                            // breaking out of an attribute in another user's
                            // browser (stored XSS through avatars).
                            if (!picture.isEmpty()
                                    && (picture.length() > 512
                                        || !picture.matches("^/api/storage/[A-Za-z0-9._/-]+$"))) {
                                return Mono.just(ResponseEntity.badRequest()
                                        .body(Map.of("message", "Invalid profile picture URL")));
                            }
                            user.setProfilePicture(picture.isEmpty() ? null : picture);
                        }

                        if (body.containsKey("defaultCurrency")) {
                            String currency = body.get("defaultCurrency").trim().toUpperCase();
                            if (!currency.matches("[A-Z]{3}")) {
                                return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "Default currency must be a 3-letter ISO code")));
                            }
                            user.setDefaultCurrency(currency);
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
    public Mono<ResponseEntity<LoginResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) Mono<RefreshTokenRequest> requestMono) {
        Mono<RefreshTokenRequest> body = requestMono == null
                ? Mono.just(new RefreshTokenRequest(null))
                : requestMono.defaultIfEmpty(new RefreshTokenRequest(null));
        return body
                .map(request -> cookieToken != null && !cookieToken.isBlank()
                        ? cookieToken : request.refreshToken())
                .flatMap(token -> {
                    if (token == null || token.isBlank()) {
                        return Mono.error(new AuthService.InvalidRefreshTokenException("Missing refresh token"));
                    }
                    return authService.refresh(token);
                })
                .map(response -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, refreshCookie(response.refreshToken()).toString())
                        .body(withoutRefresh(response)));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) Mono<LogoutRequest> requestMono) {
        Mono<LogoutRequest> body = requestMono == null
                ? Mono.just(new LogoutRequest(null))
                : requestMono.defaultIfEmpty(new LogoutRequest(null));
        return body
                .map(request -> cookieToken != null && !cookieToken.isBlank()
                        ? cookieToken : request.refreshToken())
                .flatMap(refreshToken -> authService.logout(refreshToken, authHeader))
                .thenReturn(ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                        .body(Map.of("message", "Logged out successfully")));
    }

    /** A 2-minute, single-purpose credential for the SSE stream. */
    @PostMapping("/stream-ticket")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<StreamTicketResponse>> streamTicket(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                .flatMap(user -> authService.streamTicket(
                                user.getEmail(), user.getId().toString(), user.getRole().name()))
                .map(ticket -> ResponseEntity.ok(new StreamTicketResponse(ticket)));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, String>>> changePassword(
            Authentication authentication,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody Mono<ChangePasswordRequest> requestMono) {
        String rawToken = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7) : null;
        return requestMono
                .flatMap(request -> authService.changePassword(authentication.getName(), request, rawToken))
                .thenReturn(ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                        .body(Map.of("message", "Password changed successfully")));
    }
}
