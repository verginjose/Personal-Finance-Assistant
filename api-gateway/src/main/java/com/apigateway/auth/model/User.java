package com.apigateway.auth.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Implements UserDetails so Spring Security can use this entity directly
 * without any adapter layer.
 */
@Table(name = "users", schema = "auth")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

  @Id
  private UUID id;

  @Column("email")
  private String email;

  @Column("username")
  private String username;

  @Column("password")
  private String password;

  @Column("role")
  private Role role;

  @Column("enabled")
  @Builder.Default
  private boolean enabled = true;

  @Column("account_non_locked")
  @Builder.Default
  private boolean accountNonLocked = true;

  @Column("profile_picture")
  private String profilePicture;

  // ── UserDetails contract ──────────────────────────────────────────────────

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    // Produces "ROLE_USER", "ROLE_ADMIN", etc.
    return List.of(new SimpleGrantedAuthority(role.toAuthority()));
  }

  @Override
  public String getUsername() {
    return email;
  }

  public String getActualUsername() {
    return this.username;
  }

  @Override
  public boolean isAccountNonExpired() { return true; }

  @Override
  public boolean isAccountNonLocked() { return accountNonLocked; }

  @Override
  public boolean isCredentialsNonExpired() { return true; }

  @Override
  public boolean isEnabled() { return enabled; }
}