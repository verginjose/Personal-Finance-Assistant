package com.authservice.repository;

import com.authservice.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);
  boolean existsByEmail(String email);
  boolean existsByUsername(String username);

  @Query("SELECT u FROM User u WHERE " +
         "LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
         "LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))")
  List<User> searchByUsernameOrEmail(@Param("q") String q, Pageable pageable);
}