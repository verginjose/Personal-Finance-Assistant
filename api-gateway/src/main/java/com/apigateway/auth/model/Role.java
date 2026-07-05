package com.apigateway.auth.model;

/**
 * System roles. Spring Security requires the "ROLE_" prefix when using
 * hasRole() / @PreAuthorize("hasRole(...)"). We store it WITHOUT the prefix
 * in the DB and JWT; Spring adds it automatically via GrantedAuthority.
 *
 * Usage in annotations:
 *   @PreAuthorize("hasRole('ADMIN')")          -> matches ROLE_ADMIN
 *   @PreAuthorize("hasAnyRole('ADMIN','USER')") -> matches ROLE_ADMIN or ROLE_USER
 */
public enum Role {
    USER;

    /** Returns the Spring Security GrantedAuthority string. */
    public String toAuthority() {
        return "ROLE_" + this.name();
    }
}