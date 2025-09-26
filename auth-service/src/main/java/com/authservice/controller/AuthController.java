package com.authservice.controller;

import com.authservice.dto.LoginRequestDTO;
import com.authservice.dto.LoginResponseDTO;
import com.authservice.dto.RegisterRequestDTO;
import com.authservice.dto.RegisterResponseDTO;

import com.authservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @Operation(summary = "Generate token on user login")
  @PostMapping("/login")
  public ResponseEntity<LoginResponseDTO> login(@RequestBody LoginRequestDTO loginRequestDTO) {

    Optional<String> tokenOptional = authService.authenticate(loginRequestDTO);

    if (tokenOptional.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    String token = tokenOptional.get();

    // Get user ID from userService
    String userId = authService.getUserIdByEmail(loginRequestDTO.getEmail());

    return ResponseEntity.ok(new LoginResponseDTO(token, userId));
  }
  @Operation(summary = "Register a new user account")
  @PostMapping("/register")
  public ResponseEntity<RegisterResponseDTO> register(@RequestBody RegisterRequestDTO registerRequestDTO) {

    try {
      // Check if user already exists
      if (authService.userExistsByEmail(registerRequestDTO.getEmail())) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
      }

      // Register the user (password will be bcrypted in the service)
      String userId = authService.registerUser(registerRequestDTO);

      return ResponseEntity.status(HttpStatus.CREATED)
              .body(new RegisterResponseDTO(userId, registerRequestDTO.getEmail()));

    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
  }

  @Operation(summary = "Validate Token")
  @GetMapping("/validate")
  public ResponseEntity<Void> validateToken(
      @RequestHeader("Authorization") String authHeader) {

    // Authorization: Bearer <token>
    if(authHeader == null || !authHeader.startsWith("Bearer ")) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    return authService.validateToken(authHeader.substring(7))
        ? ResponseEntity.ok().build()
        : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }
}
