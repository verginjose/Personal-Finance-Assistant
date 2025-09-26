package com.authservice.service;

import com.authservice.dto.LoginRequestDTO;
import com.authservice.dto.RegisterRequestDTO;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.util.JwtUtil;

import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserService userService;
  private final UserRepository userRepository; // Added for direct repository access needed for registration
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;

  public AuthService(UserService userService, UserRepository userRepository,
                     PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
    this.userService = userService;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtUtil = jwtUtil;
  }

  public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {
    return userService.findByEmail(loginRequestDTO.getEmail())
            .filter(u -> passwordEncoder.matches(loginRequestDTO.getPassword(),
                    u.getPassword()) && u.getRole().equals(loginRequestDTO.getRole()))
            .map(u -> jwtUtil.generateToken(u.getEmail(), u.getRole()));
  }

  public String getUserIdByEmail(String email) {
    return userService.findByEmail(email)
            .map(u -> u.getId().toString())
            .orElseThrow(() -> new RuntimeException("User not found"));
  }

  public String registerUser(RegisterRequestDTO registerRequestDTO) {
    User user = new User();
    user.setEmail(registerRequestDTO.getEmail());
    user.setPassword(passwordEncoder.encode(registerRequestDTO.getPassword()));
    user.setRole(registerRequestDTO.getRole());

    User savedUser = userRepository.save(user);
    return savedUser.getId().toString();
  }

  public boolean userExistsByEmail(String email) {
    return userService.findByEmail(email).isPresent();
  }

  public boolean validateToken(String token) {
    return jwtUtil.validateToken(token);
  }
}