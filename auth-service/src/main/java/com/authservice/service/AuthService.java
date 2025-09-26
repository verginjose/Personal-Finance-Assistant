package com.authservice.service;

import com.authservice.dto.LoginRequestDTO;
import com.authservice.util.JwtUtil;

import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserService userService;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  public AuthService(UserService userService, PasswordEncoder passwordEncoder,
      JwtUtil jwtUtil) {
    this.userService = userService;
    this.passwordEncoder = passwordEncoder;
    this.jwtUtil = jwtUtil;
  }

  public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {

      return userService.findByEmail(loginRequestDTO.getEmail())
          .filter(u -> passwordEncoder.matches(loginRequestDTO.getPassword(),
              u.getPassword())&&u.getRole().equals(loginRequestDTO.getRole()))
          .map(u -> jwtUtil.generateToken(u.getEmail(), u.getRole()));
  }
  public String getUserIdByEmail(String email) {
    return userService.findByEmail(email)
            .map(u -> u.getId().toString())
            .orElseThrow(() -> new RuntimeException("User not found"));
  }


  public boolean validateToken(String token) {
      return jwtUtil.validateToken(token);
  }
}