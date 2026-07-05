package com.apigateway.auth.repository;

import com.apigateway.auth.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository extends R2dbcRepository<User, UUID> {
  Mono<User> findByEmail(String email);
  Mono<Boolean> existsByEmail(String email);
  Mono<Boolean> existsByUsername(String username);

  @Query("SELECT * FROM auth.users WHERE " +
         "LOWER(username) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
         "LOWER(email) LIKE LOWER(CONCAT('%', :q, '%')) " +
         "LIMIT :limit OFFSET :offset")
  Flux<User> searchByUsernameOrEmail(@Param("q") String q, @Param("limit") int limit, @Param("offset") long offset);
}