package com.authservice.controller;

import com.authservice.dto.LoginRequestDTO;
import com.authservice.dto.LoginResponseDTO;
import com.authservice.dto.RegisterRequestDTO;
import com.authservice.dto.RegisterResponseDTO;
import com.authservice.service.AuthService;
import com.authservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "APIs for user authentication and registration")
public class AuthController {

  private final AuthService authService;
  private final UserService userService;

  @Operation(summary = "Login and generate JWT token")
  @PostMapping("/login")
  public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO loginRequestDTO) {
    Optional<String> tokenOptional = authService.authenticate(loginRequestDTO);

    if (tokenOptional.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String token = tokenOptional.get();
    String userId = authService.getUserIdByEmail(loginRequestDTO.getEmail());
    return ResponseEntity.ok(new LoginResponseDTO(token, userId));
  }

  @Operation(summary = "Register a new user account")
  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDTO registerRequestDTO) {
    if (userService.existsByEmail(registerRequestDTO.getEmail())) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
              .body(Map.of("message", "User with this email already exists"));
    }

    String userId = userService.createUser(registerRequestDTO);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(new RegisterResponseDTO(userId, registerRequestDTO.getEmail()));
  }

  @Operation(summary = "Validate JWT token")
  @GetMapping("/validate")
  public ResponseEntity<Void> validateToken(@RequestHeader("Authorization") String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    return authService.validateToken(authHeader.substring(7))
        ? ResponseEntity.ok().build()
        : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }

  @Operation(summary = "Health check")
  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> healthCheck() {
    return ResponseEntity.ok(Map.of("status", "UP", "service", "auth-service"));
  }
}
