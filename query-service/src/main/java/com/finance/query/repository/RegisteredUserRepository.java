package com.finance.query.repository;

import com.finance.query.model.RegisteredUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegisteredUserRepository extends JpaRepository<RegisteredUser, UUID> {
}
