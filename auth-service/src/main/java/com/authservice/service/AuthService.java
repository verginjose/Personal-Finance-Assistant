package com.authservice.service;

import com.authservice.dto.LoginRequestDTO;
import com.authservice.util.JwtUtil;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserService userService;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {
    return userService.findByEmail(loginRequestDTO.getEmail())
            .filter(u -> passwordEncoder.matches(loginRequestDTO.getPassword(),
                    u.getPassword()) && u.getRole().equals(loginRequestDTO.getRole()))
            .map(u -> {
              log.info("User authenticated: {}", u.getEmail());
              return jwtUtil.generateToken(u.getEmail(), u.getRole());
            });
  }

  public String getUserIdByEmail(String email) {
    return userService.findByEmail(email)
            .map(u -> u.getId().toString())
            .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
  }

  public boolean validateToken(String token) {
    return jwtUtil.validateToken(token);
  }
}