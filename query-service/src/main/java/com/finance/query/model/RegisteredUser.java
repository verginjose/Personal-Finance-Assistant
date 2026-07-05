package com.finance.query.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

@Entity
@Immutable
@Table(name = "users", schema = "auth")
@Getter
public class RegisteredUser {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 30)
    private String username;
}
