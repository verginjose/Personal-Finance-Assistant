package com.authservice.service;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for authentication.
 *
 * Login flow:
 *   1. Call authManager.authenticate() — Spring Security handles credential
 *      verification internally (via DaoAuthenticationProvider).
 *   2. On success, load user and issue JWT.
 *
 * This means we never compare passwords manually — Spring Security does it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;
    private final JwtService           jwtService;
    private final AuthenticationManager authenticationManager;

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // This throws BadCredentialsException / DisabledException / LockedException
        // if authentication fails. Spring Security handles all the edge cases.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow();   // safe: authenticate() above already verified existence

        String token = jwtService.generateToken(user);
        log.info("User logged in: {} [{}]", user.getEmail(), user.getRole());

        return new LoginResponse(token, user.getId().toString(), user.getEmail(), user.getRole().name());
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build();

        User saved = userRepository.save(user);
        log.info("Registered new user: {} [{}]", saved.getEmail(), saved.getRole());

        return new RegisterResponse(saved.getId().toString(), saved.getEmail(), saved.getRole().name());
    }

    // ── Inner exception (kept close to where it's thrown) ────────────────────

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String email) {
            super("Email already registered: " + email);
        }
    }
}