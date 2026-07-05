package com.finance.command.repository;

import com.finance.command.model.RegisteredUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, UUID> {
}
