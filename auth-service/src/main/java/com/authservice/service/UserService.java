package com.authservice.service;

import com.authservice.dto.RegisterRequestDTO;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public Optional<User> findByEmail(String email) {
    return userRepository.findByEmail(email);
  }

  public boolean existsByEmail(String email) {
    return userRepository.findByEmail(email).isPresent();
  }

  @Transactional
  public String createUser(RegisterRequestDTO registerRequestDTO) {
    User user = new User();
    user.setEmail(registerRequestDTO.getEmail());
    user.setPassword(passwordEncoder.encode(registerRequestDTO.getPassword()));
    user.setRole(registerRequestDTO.getRole());

    User savedUser = userRepository.save(user);
    log.info("New user registered: {}", savedUser.getEmail());
    return savedUser.getId().toString();
  }
}