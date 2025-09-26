package com.authservice.service;

import com.authservice.dto.RegisterRequestDTO;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  public Optional<User> findByEmail(String email) {
    return userRepository.findByEmail(email);
  }

  public boolean existsByEmail(String email) {
    return userRepository.findByEmail(email).isPresent();
  }

  public boolean verifyPassword(String rawPassword, String encodedPassword) {
    return passwordEncoder.matches(rawPassword, encodedPassword);
  }

  public String createUser(RegisterRequestDTO registerRequestDTO) {
    User user = new User();
    user.setEmail(registerRequestDTO.getEmail());
    user.setPassword(passwordEncoder.encode(registerRequestDTO.getPassword()));
    user.setRole(registerRequestDTO.getRole());

    User savedUser = userRepository.save(user);
    return savedUser.getId().toString();
  }
}