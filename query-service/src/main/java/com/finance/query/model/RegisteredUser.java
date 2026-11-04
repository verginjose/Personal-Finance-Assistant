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

    /**
     * The user's display currency, owned by auth-service and only read here.
     * Ledger rows are converted into it on the way out (see
     * {@link com.finance.query.service.CurrencyConversionService}).
     */
    @Column(name = "default_currency", length = 3)
    private String defaultCurrency;
}
