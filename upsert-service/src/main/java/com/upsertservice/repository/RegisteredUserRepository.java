package com.upsertservice.repository;

import com.upsertservice.model.RegisteredUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, UUID> {
}
